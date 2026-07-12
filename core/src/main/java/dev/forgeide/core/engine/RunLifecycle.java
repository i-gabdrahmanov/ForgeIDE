package dev.forgeide.core.engine;

import dev.forgeide.core.pipeline.PerTaskLoop;
import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.StepDefinition;
import dev.forgeide.core.port.HarnessGuardPort;
import dev.forgeide.core.port.ScopeDiffPort;
import dev.forgeide.core.port.StateStore;
import dev.forgeide.core.project.ProjectDefinition;
import dev.forgeide.core.run.PipelineRun;
import dev.forgeide.core.run.RunHaltReason;
import dev.forgeide.core.run.RunId;
import dev.forgeide.core.run.RunSnapshot;
import dev.forgeide.core.vars.MapVariableResolver;
import dev.forgeide.core.vars.VariableResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * T28: a run's two entry points into the actor — starting fresh ({@link #bootstrap}) and
 * resuming a persisted one after a restart ({@link #rehydrate}, SDD FR-3.4) — including FR-3.5's
 * "snapshot every prompt file at run start/resume" and FR-1.4's harness-preflight gate. Delegates
 * the audit-hash-chain replay itself to {@link ResumeReplay}. Reaches back into {@link
 * PipelineEngine} for the shared actor primitives and the {@code runs} registry.
 */
final class RunLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RunLifecycle.class);

    private final HarnessGuardPort harnessGuard;
    private final ScopeDiffPort scopeDiff;
    private final StateStore stateStore;
    private final PipelineEngine actor;

    RunLifecycle(HarnessGuardPort harnessGuard, ScopeDiffPort scopeDiff, StateStore stateStore, PipelineEngine actor) {
        this.harnessGuard = harnessGuard;
        this.scopeDiff = scopeDiff;
        this.stateStore = stateStore;
        this.actor = actor;
    }

    void bootstrap(RunId runId, ProjectDefinition project, PipelineDefinition definition, String featureSlug) {
        List<String> topLevelIds = definition.steps().stream().map(StepDefinition::id).toList();
        PipelineRun run = new PipelineRun(runId, featureSlug, topLevelIds);

        // FR-1.4's GWT: "запуск прогонов заблокирован" until the harness has been deployed with a
        // passing preflight — checked once, here, rather than per-phase like SR-8's drift check,
        // since an undeployed harness has no baseline for drift to even compare against.
        HarnessGuardPort.PreflightStatus preflight = harnessGuard.preflightStatus(project.repositoryPath());
        if (!preflight.passed()) {
            run.pause(RunHaltReason.HARNESS_PREFLIGHT);
            RunContext haltedCtx = new RunContext(run, project, definition.id(), MapVariableResolver.builder().build(),
                    Map.of(), Map.of());
            actor.registerRun(runId, haltedCtx);
            actor.persistAndPublish(haltedCtx);
            actor.audit(haltedCtx, null, 0, "run.paused",
                    AuditPayloads.haltPayload(RunHaltReason.HARNESS_PREFLIGHT.name(), preflight.detail()));
            return;
        }

        Map<String, StepDefinition> stepDefs = new LinkedHashMap<>();
        definition.steps().forEach(s -> stepDefs.put(s.id(), s));

        try {
            Map<String, Path> promptPaths = new LinkedHashMap<>();
            TemplateExpansion.collectPromptPaths(definition.steps(), "", promptPaths);
            Map<String, String> promptSnapshots = new LinkedHashMap<>();
            for (Map.Entry<String, Path> entry : promptPaths.entrySet()) {
                promptSnapshots.put(entry.getKey(), readPromptFile(project.repositoryPath(), entry.getValue()));
            }

            MapVariableResolver.Builder resolverBuilder = MapVariableResolver.builder()
                    .project("name", project.name())
                    .project("repository", project.repositoryPath().toString())
                    .feature("slug", featureSlug);
            project.paramValues().forEach(resolverBuilder::param);
            VariableResolver resolver = resolverBuilder.build();

            RunContext ctx = new RunContext(run, project, definition.id(), resolver, stepDefs, promptSnapshots);
            actor.registerRun(runId, ctx);
            actor.persistAndPublish(ctx);
            // appendAudit needs the run directory that the persistAndPublish above just created
            // (FileStateStore.save creates it) — this is the one call site where audit() runs
            // after, not before, its persistAndPublish.
            actor.audit(ctx, null, 0, "run.started",
                    AuditPayloads.runStartedPayload(featureSlug, definition, topLevelIds));
            warnIfTreeDirty(ctx, project);
            actor.advance(ctx);
        } catch (RuntimeException ex) {
            log.error("failed to start run {} for feature {}", runId, featureSlug, ex);
            run.pause(RunHaltReason.ENGINE_ERROR);
            RunContext failedCtx = new RunContext(run, project, definition.id(), MapVariableResolver.builder().build(),
                    stepDefs, Map.of());
            actor.registerRun(runId, failedCtx);
            actor.persistAndPublish(failedCtx);
            actor.audit(failedCtx, null, 0, "run.paused",
                    AuditPayloads.haltPayload(RunHaltReason.ENGINE_ERROR.name(), String.valueOf(ex.getMessage())));
        }
    }

    /**
     * T36/SR-6: scope-diff (see {@code GitScopeDiff}'s class doc) only ever compares status codes
     * before/after a phase — a path already dirty when the run starts keeps its pre-existing code
     * for the whole run and is silently exempt from every phase's {@code allowed_write} check.
     * That trade-off is deliberate (a full content walk would blow NFR-4's per-phase budget), but
     * it must not be something a human only discovers by reading {@code GitScopeDiff}'s Javadoc —
     * hence a loud, once-per-run audit entry the moment it's known to apply. Never blocks the run;
     * "clean tree" is silent, so a normal start looks exactly as it did before this check existed.
     */
    private void warnIfTreeDirty(RunContext ctx, ProjectDefinition project) {
        ScopeDiffPort.Snapshot snapshot = scopeDiff.snapshot(project.repositoryPath());
        if (!snapshot.statusByPath().isEmpty()) {
            actor.audit(ctx, null, 0, "run.dirty_tree",
                    AuditPayloads.dirtyTreePayload(List.copyOf(snapshot.statusByPath().keySet())));
        }
    }

    void rehydrate(ProjectDefinition project, PipelineDefinition definition, RunId runId) {
        if (actor.hasRun(runId)) {
            return;
        }
        Optional<RunSnapshot> loaded = stateStore.load(runId);
        if (loaded.isEmpty()) {
            log.warn("resume requested for unknown run {}", runId);
            return;
        }
        RunSnapshot snapshot = loaded.get();
        PipelineRun run = PipelineRun.restore(snapshot);

        try {
            Map<String, StepDefinition> stepDefs = new LinkedHashMap<>();
            definition.steps().forEach(s -> stepDefs.put(s.id(), s));
            Map<String, String> templateKeyOf = new LinkedHashMap<>();
            for (StepDefinition def : definition.steps()) {
                if (def instanceof PerTaskLoop loop) {
                    ResumeReplay.reExpandIfAlreadyPassed(project, run, loop, stepDefs, templateKeyOf);
                }
            }

            // FR-3.5's "read once at run start" boundary is this resume, not the original (now
            // gone) process's bootstrap — there is nowhere the literal prompt text of that process
            // could have survived a kill -9, so the current file is what this run's remainder
            // executes with, going forward, from here.
            Map<String, Path> promptPaths = new LinkedHashMap<>();
            TemplateExpansion.collectPromptPaths(definition.steps(), "", promptPaths);
            Map<String, String> promptSnapshots = new LinkedHashMap<>();
            for (Map.Entry<String, Path> entry : promptPaths.entrySet()) {
                promptSnapshots.put(entry.getKey(), readPromptFile(project.repositoryPath(), entry.getValue()));
            }

            MapVariableResolver.Builder resolverBuilder = MapVariableResolver.builder()
                    .project("name", project.name())
                    .project("repository", project.repositoryPath().toString())
                    .feature("slug", snapshot.featureSlug());
            project.paramValues().forEach(resolverBuilder::param);
            VariableResolver resolver = resolverBuilder.build();

            RunContext ctx = new RunContext(run, project, definition.id(), resolver, stepDefs, promptSnapshots);
            ctx.templateKeyOf.putAll(templateKeyOf);
            ResumeReplay.replayContext(ctx, stateStore.loadAudit(runId));

            actor.registerRun(runId, ctx);
            actor.audit(ctx, null, 0, "run.resumed", AuditPayloads.empty());
            actor.persistAndPublish(ctx);
            actor.advance(ctx);
        } catch (RuntimeException ex) {
            log.error("failed to resume run {}", runId, ex);
            run.pause(RunHaltReason.ENGINE_ERROR);
            RunContext failedCtx = new RunContext(run, project, definition.id(), MapVariableResolver.builder().build(),
                    Map.of(), Map.of());
            actor.registerRun(runId, failedCtx);
            actor.persistAndPublish(failedCtx);
            actor.audit(failedCtx, null, 0, "run.paused",
                    AuditPayloads.haltPayload(RunHaltReason.ENGINE_ERROR.name(), String.valueOf(ex.getMessage())));
        }
    }

    static String readPromptFile(Path root, Path relative) {
        try {
            return Files.readString(root.resolve(relative));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read prompt template: " + relative, e);
        }
    }
}
