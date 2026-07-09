package dev.forgeide.core.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.forgeide.core.audit.AuditEvent;
import dev.forgeide.core.engine.support.FixtureAgentRuntimePort;
import dev.forgeide.core.engine.support.FixtureHarnessGuardPort;
import dev.forgeide.core.engine.support.FixtureScriptRunnerPort;
import dev.forgeide.core.engine.support.InMemoryStateStore;
import dev.forgeide.core.engine.support.TestProjects;
import dev.forgeide.core.event.EngineCommand;
import dev.forgeide.core.pipeline.AgentStep;
import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.policy.RetryPolicy;
import dev.forgeide.core.policy.TokenBudget;
import dev.forgeide.core.port.AgentResult;
import dev.forgeide.core.port.ManifestProjectorPort;
import dev.forgeide.core.port.OutwardActionsPort;
import dev.forgeide.core.port.ScopeDiffPort;
import dev.forgeide.core.port.SecretStorePort;
import dev.forgeide.core.port.TokenUsage;
import dev.forgeide.core.run.HarnessDriftAction;
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
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static dev.forgeide.core.engine.support.Await.until;
import static dev.forgeide.core.engine.support.Snapshots.statusOf;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * T18 acceptance (Т-7/SR-8's GWT): "агент в фазе правит .gigacode/hooks/tdd-guard.py; движок
 * готовит следующую агент-фазу; STOPPED(harness-drift) с диффом" — plus the "принять дифф"/
 * "откатить" recovery FR-8.3 offers.
 */
class PipelineEngineHarnessDriftTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TokenBudget BUDGET = new TokenBudget(1_000, Duration.ofMinutes(5), 10);

    @Test
    void driftBeforeTheNextAgentPhaseStopsTheRunThenAcceptResumesIt(@TempDir Path repo) throws IOException {
        PipelineDefinition definition = twoStepDefinition(repo);
        FixtureHarnessGuardPort harnessGuard = new FixtureHarnessGuardPort();
        FixtureAgentRuntimePort agentRuntime = agentThatDriftsAfter("work", harnessGuard);
        InMemoryStateStore stateStore = new InMemoryStateStore();

        try (PipelineEngine engine = engine(stateStore, agentRuntime, harnessGuard)) {
            RunId runId = engine.start(TestProjects.minimal(repo), definition, "feature-x");

            until(() -> engine.snapshot(runId).map(s -> s.status() == RunStatus.STOPPED).orElse(false));
            RunSnapshot stopped = engine.snapshot(runId).orElseThrow();
            assertThat(stopped.haltReason()).contains(RunHaltReason.HARNESS_DRIFT);
            assertThat(statusOf(stopped, "work")).isEqualTo(StepStatus.PASSED);
            assertThat(statusOf(stopped, "build")).isEqualTo(StepStatus.READY);

            AuditEvent stoppedEvent = stateStore.loadAudit(runId).stream()
                    .filter(e -> e.type().equals("run.stopped")).findFirst().orElseThrow();
            assertThat(stoppedEvent.payload().get("reason").asText()).isEqualTo("HARNESS_DRIFT");
            assertThat(stoppedEvent.payload().get("detail").asText()).contains("tdd-guard.py");

            engine.submit(new EngineCommand.HarnessDriftResolved(runId, HarnessDriftAction.ACCEPT, "tester", Instant.now()));

            until(() -> engine.snapshot(runId).map(s -> s.status() == RunStatus.COMPLETED).orElse(false));
            assertThat(harnessGuard.acceptCalls.get()).isEqualTo(1);
            assertThat(harnessGuard.rollbackCalls.get()).isZero();
            AuditEvent accepted = stateStore.loadAudit(runId).stream()
                    .filter(e -> e.type().equals("harness.drift.accepted")).findFirst().orElseThrow();
            assertThat(accepted.payload().get("user").asText()).isEqualTo("tester");
            assertThat(accepted.payload().get("action").asText()).isEqualTo("accept");
        }
    }

    @Test
    void rollbackAlsoResumesTheRunAndIsRecordedInTheAudit(@TempDir Path repo) throws IOException {
        PipelineDefinition definition = twoStepDefinition(repo);
        FixtureHarnessGuardPort harnessGuard = new FixtureHarnessGuardPort();
        FixtureAgentRuntimePort agentRuntime = agentThatDriftsAfter("work", harnessGuard);
        InMemoryStateStore stateStore = new InMemoryStateStore();

        try (PipelineEngine engine = engine(stateStore, agentRuntime, harnessGuard)) {
            RunId runId = engine.start(TestProjects.minimal(repo), definition, "feature-x");
            until(() -> engine.snapshot(runId).map(s -> s.status() == RunStatus.STOPPED).orElse(false));

            engine.submit(new EngineCommand.HarnessDriftResolved(runId, HarnessDriftAction.ROLLBACK, "tester", Instant.now()));

            until(() -> engine.snapshot(runId).map(s -> s.status() == RunStatus.COMPLETED).orElse(false));
            assertThat(harnessGuard.rollbackCalls.get()).isEqualTo(1);
            assertThat(harnessGuard.acceptCalls.get()).isZero();
            AuditEvent rolledBack = stateStore.loadAudit(runId).stream()
                    .filter(e -> e.type().equals("harness.drift.rolledback")).findFirst().orElseThrow();
            assertThat(rolledBack.payload().get("restored")).anySatisfy(n -> assertThat(n.asText()).contains("tdd-guard.py"));
        }
    }

    private static PipelineDefinition twoStepDefinition(Path repo) throws IOException {
        Path promptDir = repo.resolve("prompts");
        Files.createDirectories(promptDir);
        Files.writeString(promptDir.resolve("work.md"), "work");
        Files.writeString(promptDir.resolve("build.md"), "build");

        AgentStep work = new AgentStep("work", List.of(), "claude", Path.of("prompts/work.md"),
                List.of(), List.of(), List.of(), RetryPolicy.DEFAULT, BUDGET);
        AgentStep build = new AgentStep("build", List.of("work"), "claude", Path.of("prompts/build.md"),
                List.of(), List.of(), List.of(), RetryPolicy.DEFAULT, BUDGET);
        return new PipelineDefinition("p", 1, List.of(work, build));
    }

    /** Simulates Т-7: while the named step's phase is running, it edits the harness — set here,
     * synchronously, inside the phase's own handler, so there is no race against the engine
     * reading it before the next phase's pre-dispatch drift check. */
    private static FixtureAgentRuntimePort agentThatDriftsAfter(String stepPrompt, FixtureHarnessGuardPort harnessGuard) {
        return new FixtureAgentRuntimePort(invocation -> {
            if (invocation.prompt().equals(stepPrompt)) {
                harnessGuard.drifted = true;
            }
            ObjectNode json = MAPPER.createObjectNode();
            json.put("step_id", invocation.prompt());
            return new AgentResult(0, Optional.of(json), new TokenUsage(1, 1), Path.of("raw.log"));
        });
    }

    private static PipelineEngine engine(InMemoryStateStore stateStore, FixtureAgentRuntimePort agentRuntime,
                                          FixtureHarnessGuardPort harnessGuard) {
        return new PipelineEngine(stateStore, agentRuntime, FixtureScriptRunnerPort.alwaysOk(),
                ManifestProjectorPort.NOOP, ScopeDiffPort.NOOP, SecretStorePort.NOOP, OutwardActionsPort.NOOP,
                harnessGuard);
    }
}
