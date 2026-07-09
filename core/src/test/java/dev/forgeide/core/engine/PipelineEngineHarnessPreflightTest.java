package dev.forgeide.core.engine;

import dev.forgeide.core.audit.AuditEvent;
import dev.forgeide.core.engine.support.FixtureAgentRuntimePort;
import dev.forgeide.core.engine.support.FixtureHarnessGuardPort;
import dev.forgeide.core.engine.support.FixtureScriptRunnerPort;
import dev.forgeide.core.engine.support.InMemoryStateStore;
import dev.forgeide.core.engine.support.TestProjects;
import dev.forgeide.core.pipeline.AgentStep;
import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.policy.RetryPolicy;
import dev.forgeide.core.policy.TokenBudget;
import dev.forgeide.core.port.ManifestProjectorPort;
import dev.forgeide.core.port.OutwardActionsPort;
import dev.forgeide.core.port.ScopeDiffPort;
import dev.forgeide.core.port.SecretStorePort;
import dev.forgeide.core.run.RunHaltReason;
import dev.forgeide.core.run.RunId;
import dev.forgeide.core.run.RunSnapshot;
import dev.forgeide.core.run.RunStatus;
import dev.forgeide.core.run.StepStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static dev.forgeide.core.engine.support.Await.until;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * T18 acceptance: "прогон без preflight-PASS не стартует" (SDD FR-1.4's GWT — undeployed/failed
 * harness -> "enforcement off", the run never turns {@code RUNNING}, no phase ever dispatches).
 */
class PipelineEngineHarnessPreflightTest {

    private static final TokenBudget BUDGET = new TokenBudget(1_000, Duration.ofMinutes(5), 10);

    @Test
    void runDoesNotStartWithoutAPassingPreflight(@TempDir Path repo) throws IOException {
        Path promptDir = repo.resolve("prompts");
        Files.createDirectories(promptDir);
        Files.writeString(promptDir.resolve("work.md"), "work");
        AgentStep work = new AgentStep("work", List.of(), "claude", Path.of("prompts/work.md"),
                List.of(), List.of(), List.of(), RetryPolicy.DEFAULT, BUDGET);
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(work));

        FixtureHarnessGuardPort harnessGuard = new FixtureHarnessGuardPort();
        harnessGuard.preflightPassed = false;
        harnessGuard.preflightDetail = "missing .gigacode/settings.hooks.json";

        InMemoryStateStore stateStore = new InMemoryStateStore();
        FixtureAgentRuntimePort agentRuntime = new FixtureAgentRuntimePort(inv -> {
            throw new AssertionError("agent runtime should not be called: preflight did not pass");
        });

        try (PipelineEngine engine = new PipelineEngine(stateStore, agentRuntime, FixtureScriptRunnerPort.alwaysOk(),
                ManifestProjectorPort.NOOP, ScopeDiffPort.NOOP, SecretStorePort.NOOP, OutwardActionsPort.NOOP,
                harnessGuard)) {
            RunId runId = engine.start(TestProjects.minimal(repo), definition, "feature-x");

            until(() -> engine.snapshot(runId).map(s -> s.status() == RunStatus.PAUSED).orElse(false));
            RunSnapshot snapshot = engine.snapshot(runId).orElseThrow();
            assertThat(snapshot.haltReason()).contains(RunHaltReason.HARNESS_PREFLIGHT);
            assertThat(dev.forgeide.core.engine.support.Snapshots.statusOf(snapshot, "work")).isEqualTo(StepStatus.PENDING);

            AuditEvent paused = stateStore.loadAudit(runId).stream()
                    .filter(e -> e.type().equals("run.paused")).findFirst().orElseThrow();
            assertThat(paused.payload().get("reason").asText()).isEqualTo("HARNESS_PREFLIGHT");
            assertThat(paused.payload().get("detail").asText()).contains("settings.hooks.json");
        }
    }
}
