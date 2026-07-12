package dev.forgeide.core.engine;

import dev.forgeide.core.event.EngineCommand;
import dev.forgeide.core.pipeline.JudgeStep;
import dev.forgeide.core.pipeline.OutwardAction;
import dev.forgeide.core.pipeline.OutwardStep;
import dev.forgeide.core.pipeline.StepDefinition;
import dev.forgeide.core.port.OutwardActionException;
import dev.forgeide.core.port.OutwardActionsPort;
import dev.forgeide.core.port.SecretStorePort;
import dev.forgeide.core.run.FailureReason;
import dev.forgeide.core.run.RunId;
import dev.forgeide.core.run.RunStatus;
import dev.forgeide.core.run.StepRun;
import dev.forgeide.core.run.StepStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * T28 "outward-шаги и их пред-условия": {@code git_push}/{@code create_pr}/{@code jira_comment}/
 * {@code jira_transition} (T17/SR-4) — runs on the engine's worker threads, never inside an agent
 * phase's own process. Reaches back into {@link PipelineEngine} for the shared actor primitives.
 */
final class OutwardCoordinator {

    private static final Logger log = LoggerFactory.getLogger(OutwardCoordinator.class);

    private final OutwardActionsPort outwardActions;
    private final SecretStorePort secretStore;
    private final ExecutorService workers;
    private final PhaseDispatcher phaseDispatcher;
    private final PipelineEngine actor;

    OutwardCoordinator(OutwardActionsPort outwardActions, SecretStorePort secretStore, ExecutorService workers,
                        PhaseDispatcher phaseDispatcher, PipelineEngine actor) {
        this.outwardActions = outwardActions;
        this.secretStore = secretStore;
        this.workers = workers;
        this.phaseDispatcher = phaseDispatcher;
        this.actor = actor;
    }

    /**
     * T17/SR-4: {@code outward} actions run here, on the engine's own worker thread — never
     * inside an agent phase's process. Before touching anything external, re-checks (defense in
     * depth, SD §4's "the engine has the final say", not just {@link
     * dev.forgeide.core.pipeline.validation.PipelineValidator}'s load-time graph check) that
     * every judge upstream of this step actually reached {@code PASSED}; a {@code SCOPE}/{@code
     * TAMPERED} failure anywhere upstream can never reach here in the first place (those block
     * manual retry — {@link FailureReason#blocksManualRetry}), so that half of the T17 scope
     * note's precondition ("чистый scope-diff последней агент-фазы") is structural, not something
     * this method can meaningfully re-verify on its own.
     */
    void dispatchOutward(RunContext ctx, OutwardStep outward) {
        RunId runId = ctx.run.id();
        int iteration = phaseDispatcher.markRunning(ctx, outward.id());

        Optional<String> unpassedJudge = firstUnpassedUpstreamJudge(ctx, outward);
        if (unpassedJudge.isPresent()) {
            actor.submit(new EngineCommand.StepFailed(runId, outward.id(), iteration, FailureReason.JUDGE,
                    "upstream judge not passed: " + unpassedJudge.get()));
            return;
        }

        String branch = outwardBranch(ctx, outward);
        String prBase = stackedPrBase(ctx, outward);
        Map<String, String> env = secretStore.resolve(outward.envScope());
        workers.execute(() -> {
            try {
                Map<String, String> resultRefs = new LinkedHashMap<>();
                for (OutwardAction action : outward.actions()) {
                    switch (action) {
                        case GIT_PUSH -> resultRefs.putAll(runGitPush(ctx, outward, branch, env));
                        case CREATE_PR -> resultRefs.putAll(runCreatePr(ctx, outward, branch, prBase, env));
                        case JIRA_COMMENT -> resultRefs.putAll(runJiraComment(ctx, outward, resultRefs, env));
                        case JIRA_TRANSITION -> resultRefs.putAll(runJiraTransition(ctx, outward, env));
                    }
                }
                actor.submit(new EngineCommand.OutwardCompleted(runId, outward.id(), iteration, branch, resultRefs));
            } catch (OutwardActionException | RuntimeException ex) {
                actor.submit(new EngineCommand.StepFailed(runId, outward.id(), iteration, FailureReason.SCRIPT,
                        String.valueOf(ex.getMessage())));
            }
        });
    }

    /** BFS over {@code dependsOn} (same shape as {@code PipelineValidator#upstreamJudge}, just
     * over live {@link StepStatus} instead of the static graph) for the first judge that has not
     * reached {@code PASSED}. Empty in the overwhelming common case — {@code depsSatisfied}
     * already guarantees every direct dependency is {@code PASSED} before {@code dispatch} ever
     * runs — this only ever fires if a future change loosens that guarantee. */
    private Optional<String> firstUnpassedUpstreamJudge(RunContext ctx, StepDefinition outward) {
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(outward.dependsOn());
        while (!queue.isEmpty()) {
            String id = queue.poll();
            if (!seen.add(id)) {
                continue;
            }
            StepDefinition step = ctx.stepDefs.get(id);
            if (step == null) {
                continue;
            }
            if (step instanceof JudgeStep && ctx.run.step(id).status() != StepStatus.PASSED) {
                return Optional.of(id);
            }
            queue.addAll(step.dependsOn());
        }
        return Optional.empty();
    }

    /** Deterministic, per-outward-step branch name: stable across a retry (same commit, same
     * branch — the entire idempotency argument for {@code git_push}/{@code create_pr}), unique
     * across sibling outward steps in the same run (a {@code per_task_loop} can expand several). */
    private static String outwardBranch(RunContext ctx, OutwardStep outward) {
        return ctx.run.featureSlug() + "/" + outward.id();
    }

    /** T17 "stacked по depends_on": if an earlier outward step in this run's {@code dependsOn}
     * closure already pushed a branch, {@code create_pr} bases on top of it instead of the
     * project's single configured target branch — the stacked-PR pattern feature-pipeline's own
     * multi-task delivery relies on. Falls back to {@link dev.forgeide.core.project.OutwardConfig#targetBranch()}
     * when no such predecessor exists. */
    private String stackedPrBase(RunContext ctx, OutwardStep outward) {
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(outward.dependsOn());
        while (!queue.isEmpty()) {
            String id = queue.poll();
            if (!seen.add(id)) {
                continue;
            }
            String branch = ctx.outwardBranches.get(id);
            if (branch != null) {
                return branch;
            }
            StepDefinition step = ctx.stepDefs.get(id);
            if (step == null) {
                continue;
            }
            queue.addAll(step.dependsOn());
        }
        return ctx.project.outward().targetBranch();
    }

    private Map<String, String> runGitPush(RunContext ctx, OutwardStep outward, String branch,
                                            Map<String, String> env) throws OutwardActionException {
        String remote = ctx.project.outward().gitRemote();
        String message = "forgeide: " + ctx.run.featureSlug() + " (" + outward.id() + ")";
        var request = new OutwardActionsPort.GitPushRequest(ctx.projectRoot, remote, branch, message, env);
        return outwardActions.gitPush(request).resultRefs();
    }

    private Map<String, String> runCreatePr(RunContext ctx, OutwardStep outward, String branch, String base,
                                             Map<String, String> env) throws OutwardActionException {
        var repo = ctx.project.outward().pr().orElseThrow(() -> new OutwardActionException(
                "outward step '" + outward.id() + "': create_pr requires the project's outward.pr config"));
        String title = "forgeide: " + ctx.run.featureSlug();
        String body = "Automated delivery via ForgeIDE outward step '" + outward.id() + "' (run " + ctx.run.id() + ").";
        var request = new OutwardActionsPort.CreatePrRequest(ctx.projectRoot, repo, branch, base, title, body, env);
        return outwardActions.createPr(request).resultRefs();
    }

    private Map<String, String> runJiraComment(RunContext ctx, OutwardStep outward, Map<String, String> resultRefsSoFar,
                                                Map<String, String> env) throws OutwardActionException {
        var jira = ctx.project.outward().jira().orElseThrow(() -> new OutwardActionException(
                "outward step '" + outward.id() + "': jira_comment requires the project's outward.jira config"));
        String issueKey = ctx.resolver.render("${params.jira_key}");
        String prUrl = resultRefsSoFar.get("pr_url");
        String comment = "ForgeIDE: delivered run " + ctx.run.id() + " (" + ctx.run.featureSlug() + ")."
                + (prUrl != null ? " PR: " + prUrl : "");
        var request = new OutwardActionsPort.JiraCommentRequest(jira, issueKey, comment, env);
        return outwardActions.jiraComment(request).resultRefs();
    }

    private Map<String, String> runJiraTransition(RunContext ctx, OutwardStep outward, Map<String, String> env)
            throws OutwardActionException {
        var jira = ctx.project.outward().jira().orElseThrow(() -> new OutwardActionException(
                "outward step '" + outward.id() + "': jira_transition requires the project's outward.jira config"));
        String issueKey = ctx.resolver.render("${params.jira_key}");
        var request = new OutwardActionsPort.JiraTransitionRequest(jira, issueKey, env);
        return outwardActions.jiraTransition(request).resultRefs();
    }

    void handleOutwardCompleted(RunContext ctx, EngineCommand.OutwardCompleted cmd) {
        if (ctx.run.status() != RunStatus.RUNNING) {
            return;
        }
        StepRun sr = ctx.run.step(cmd.stepId());
        if (sr.status() != StepStatus.RUNNING || sr.iteration() != cmd.iteration()) {
            log.debug("stale OutwardCompleted for {} iter {} (current {} iter {})",
                    cmd.stepId(), cmd.iteration(), sr.status(), sr.iteration());
            return;
        }
        ctx.outwardBranches.put(cmd.stepId(), cmd.branch());
        sr.transitionTo(StepStatus.PASSED);
        ctx.autoRetryCounts.remove(cmd.stepId());
        actor.audit(ctx, cmd.stepId(), cmd.iteration(), "outward.result", AuditPayloads.outwardResultPayload(cmd));
        actor.persistAndPublish(ctx);
        actor.advance(ctx);
    }
}
