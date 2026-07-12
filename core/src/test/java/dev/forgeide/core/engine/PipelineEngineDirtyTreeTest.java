package dev.forgeide.core.engine;

import dev.forgeide.core.audit.AuditEvent;
import dev.forgeide.core.engine.support.FixtureAgentRuntimePort;
import dev.forgeide.core.engine.support.FixtureScriptRunnerPort;
import dev.forgeide.core.engine.support.InMemoryStateStore;
import dev.forgeide.core.engine.support.TestProjects;
import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.ScriptStep;
import dev.forgeide.core.port.ManifestProjectorPort;
import dev.forgeide.core.port.ScopeDiffPort;
import dev.forgeide.core.port.SecretStorePort;
import dev.forgeide.core.run.RunId;
import dev.forgeide.core.run.RunStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.forgeide.core.engine.support.Await.until;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * T36 acceptance (SDD SR-6/Т-13; ревью импортёра 2026-07-11 №5): starting a run on a working tree
 * that is already dirty silently weakens scope-diff for the whole run — {@code GitScopeDiff} only
 * ever compares status codes before/after a phase, so a path dirty before the run started keeps
 * its pre-existing code and is never re-flagged. That trade-off is real (SR-6/NFR-4), but the
 * human must be told, not left to find it in Javadoc — a one-time, non-blocking audit entry at run
 * start.
 */
class PipelineEngineDirtyTreeTest {

    @Test
    void dirtyTreeAtRunStartEmitsWarningWithoutBlockingTheRun(@TempDir Path repo) {
        ScriptStep a = new ScriptStep("a", List.of(), List.of("build"), Duration.ofSeconds(5));
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(a));
        InMemoryStateStore stateStore = new InMemoryStateStore();
        ScopeDiffPort dirtyAtStart = fakeSnapshot(Map.of("already-dirty.txt", "M "));

        try (PipelineEngine engine = new PipelineEngine(stateStore, throwingAgent(), FixtureScriptRunnerPort.alwaysOk(),
                ManifestProjectorPort.NOOP, dirtyAtStart, SecretStorePort.NOOP)) {
            RunId runId = engine.start(TestProjects.minimal(repo), definition, "feature-x");

            until(() -> engine.snapshot(runId).map(s -> s.status() == RunStatus.COMPLETED).orElse(false));

            AuditEvent warning = stateStore.loadAudit(runId).stream()
                    .filter(e -> e.type().equals("run.dirty_tree")).findFirst().orElseThrow();
            assertThat(warning.stepId()).isNull();
            assertThat(warning.payload().get("paths")).extracting(com.fasterxml.jackson.databind.JsonNode::asText)
                    .containsExactly("already-dirty.txt");
        }
    }

    @Test
    void cleanTreeAtRunStartEmitsNoDirtyTreeWarning(@TempDir Path repo) {
        ScriptStep a = new ScriptStep("a", List.of(), List.of("build"), Duration.ofSeconds(5));
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(a));
        InMemoryStateStore stateStore = new InMemoryStateStore();

        try (PipelineEngine engine = new PipelineEngine(stateStore, throwingAgent(), FixtureScriptRunnerPort.alwaysOk(),
                ManifestProjectorPort.NOOP, ScopeDiffPort.NOOP, SecretStorePort.NOOP)) {
            RunId runId = engine.start(TestProjects.minimal(repo), definition, "feature-x");

            until(() -> engine.snapshot(runId).map(s -> s.status() == RunStatus.COMPLETED).orElse(false));

            assertThat(stateStore.loadAudit(runId)).extracting(AuditEvent::type).doesNotContain("run.dirty_tree");
        }
    }

    private static ScopeDiffPort fakeSnapshot(Map<String, String> statusByPath) {
        return new ScopeDiffPort() {
            @Override
            public Snapshot snapshot(Path projectRoot) {
                return new Snapshot(new LinkedHashMap<>(statusByPath), null);
            }

            @Override
            public List<String> violations(Path projectRoot, Snapshot before, List<String> allowedWrite) {
                return List.of();
            }

            @Override
            public List<String> rollback(Path projectRoot, List<String> violations) {
                return List.of();
            }
        };
    }

    private static FixtureAgentRuntimePort throwingAgent() {
        return new FixtureAgentRuntimePort(inv -> {
            throw new AssertionError("agent runtime should not be called in this scenario");
        });
    }
}
