package dev.forgeide.core.engine;

import dev.forgeide.core.audit.AuditEvent;
import dev.forgeide.core.engine.support.FixtureAgentRuntimePort;
import dev.forgeide.core.engine.support.FixtureScriptRunnerPort;
import dev.forgeide.core.engine.support.InMemoryStateStore;
import dev.forgeide.core.engine.support.TestProjects;
import dev.forgeide.core.event.EngineCommand;
import dev.forgeide.core.pipeline.AgentStep;
import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.ScriptStep;
import dev.forgeide.core.policy.RetryPolicy;
import dev.forgeide.core.policy.TokenBudget;
import dev.forgeide.core.port.AgentResult;
import dev.forgeide.core.port.ScriptResult;
import dev.forgeide.core.port.TokenUsage;
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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.forgeide.core.engine.support.Await.until;
import static dev.forgeide.core.engine.support.Snapshots.statusOf;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * T11 acceptance: per-step auto-retry (FR-11.2), the generic actor-exception catch (FR-11.4),
 * and the retry-time prompt-drift warning (FR-3.5 traceability row T-12).
 */
class PipelineEngineRetryTest {

    private static final TokenBudget BUDGET = new TokenBudget(1_000, Duration.ofMinutes(5), 10);

    private static FixtureAgentRuntimePort throwingAgent() {
        return new FixtureAgentRuntimePort(inv -> {
            throw new AssertionError("unused in this test");
        });
    }

    @Test
    void scriptStepAutoRetriesOnceThenSucceeds(@TempDir Path repo) {
        ScriptStep a = new ScriptStep("a", List.of(), List.of("flaky"), Duration.ofSeconds(5), new RetryPolicy(0, 1));
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(a));
        InMemoryStateStore stateStore = new InMemoryStateStore();
        AtomicInteger calls = new AtomicInteger();
        FixtureScriptRunnerPort scriptRunner = new FixtureScriptRunnerPort(inv ->
                calls.incrementAndGet() == 1 ? new ScriptResult(1, "", "boom") : new ScriptResult(0, "ok", ""));

        try (PipelineEngine engine = new PipelineEngine(stateStore, throwingAgent(), scriptRunner)) {
            RunId runId = engine.start(TestProjects.minimal(repo), definition, "feature-x");

            until(() -> engine.snapshot(runId).map(s -> s.status() == RunStatus.COMPLETED).orElse(false));

            assertThat(statusOf(engine.snapshot(runId).orElseThrow(), "a")).isEqualTo(StepStatus.PASSED);
            assertThat(calls.get()).isEqualTo(2);
            AuditEvent retried = stateStore.audit().stream()
                    .filter(e -> e.type().equals("step.retried")).findFirst().orElseThrow();
            assertThat(retried.payload().get("auto").asBoolean()).isTrue();
            assertThat(retried.payload().get("attempt").asInt()).isEqualTo(1);
            assertThat(retried.payload().get("max").asInt()).isEqualTo(1);
        }
    }

    @Test
    void scriptStepAutoRetryExhaustsThenTerminalUntilManualRetry(@TempDir Path repo) {
        ScriptStep a = new ScriptStep("a", List.of(), List.of("flaky"), Duration.ofSeconds(5), new RetryPolicy(0, 1));
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(a));
        InMemoryStateStore stateStore = new InMemoryStateStore();
        AtomicInteger calls = new AtomicInteger();
        // Fails the auto-retried first two attempts, succeeds on the third (the manual retry).
        FixtureScriptRunnerPort scriptRunner = new FixtureScriptRunnerPort(inv ->
                calls.incrementAndGet() <= 2 ? new ScriptResult(1, "", "boom") : new ScriptResult(0, "ok", ""));

        try (PipelineEngine engine = new PipelineEngine(stateStore, throwingAgent(), scriptRunner)) {
            RunId runId = engine.start(TestProjects.minimal(repo), definition, "feature-x");

            // Terminal FAILED is only ever observable once the retry budget is spent (a step
            // mid auto-retry goes straight back to RUNNING, never a transient FAILED).
            until(() -> engine.snapshot(runId).map(s -> statusOf(s, "a") == StepStatus.FAILED).orElse(false));
            assertThat(calls.get()).isEqualTo(2);
            assertThat(stateStore.audit().stream().filter(e -> e.type().equals("step.failed")).count()).isEqualTo(2);
            assertThat(stateStore.audit().stream().filter(e -> e.type().equals("step.retried")).count()).isEqualTo(1);

            engine.submit(new EngineCommand.RetryStep(runId, "a"));

            until(() -> engine.snapshot(runId).map(s -> s.status() == RunStatus.COMPLETED).orElse(false));
            assertThat(calls.get()).isEqualTo(3);
        }
    }

    @Test
    void agentStepAutoRetriesAStreamFailureByDefault(@TempDir Path repo) throws IOException {
        Path promptDir = repo.resolve("prompts");
        Files.createDirectories(promptDir);
        Files.writeString(promptDir.resolve("work.md"), "Do the thing");

        AgentStep agent = new AgentStep("work", List.of(), "claude", Path.of("prompts/work.md"),
                List.of(), List.of(), List.of(), RetryPolicy.DEFAULT, BUDGET);
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(agent));
        InMemoryStateStore stateStore = new InMemoryStateStore();

        AtomicInteger calls = new AtomicInteger();
        FixtureAgentRuntimePort agentRuntime = new FixtureAgentRuntimePort(inv -> {
            if (calls.incrementAndGet() == 1) {
                return new AgentResult(0, Optional.empty(), new TokenUsage(1, 1), Path.of("raw.log"));
            }
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var json = mapper.createObjectNode();
            json.put("step_id", "work");
            return new AgentResult(0, Optional.of(json), new TokenUsage(1, 1), Path.of("raw.log"));
        });

        try (PipelineEngine engine = new PipelineEngine(stateStore, agentRuntime, FixtureScriptRunnerPort.alwaysOk())) {
            RunId runId = engine.start(TestProjects.minimal(repo), definition, "feature-x");

            until(() -> engine.snapshot(runId).map(s -> s.status() == RunStatus.COMPLETED).orElse(false));
            assertThat(calls.get()).isEqualTo(2);
        }
    }

    @Test
    void unhandledExceptionInCommandHandlingPausesJustThatRun(@TempDir Path repo) {
        ScriptStep a = new ScriptStep("a", List.of(), List.of("boom"), Duration.ofSeconds(5));
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(a));
        InMemoryStateStore stateStore = new InMemoryStateStore();
        FixtureScriptRunnerPort scriptRunner = new FixtureScriptRunnerPort(inv -> new ScriptResult(1, "", "boom"));

        try (PipelineEngine engine = new PipelineEngine(stateStore, throwingAgent(), scriptRunner)) {
            RunId runId = engine.start(TestProjects.minimal(repo), definition, "feature-x");
            until(() -> engine.snapshot(runId).map(s -> statusOf(s, "a") == StepStatus.FAILED).orElse(false));
            assertThat(engine.snapshot(runId).orElseThrow().status()).isEqualTo(RunStatus.RUNNING);

            // StepCompleted for a step id the run does not have throws NoSuchElementException
            // inside handleStepCompleted — exactly the kind of actor-thread bug FR-11.4 targets.
            engine.submit(new EngineCommand.StepCompleted(runId, "does-not-exist", 1, List.of()));

            until(() -> engine.snapshot(runId).map(s -> s.status() == RunStatus.PAUSED).orElse(false));
            RunSnapshot paused = engine.snapshot(runId).orElseThrow();
            assertThat(paused.haltReason()).contains(RunHaltReason.ENGINE_ERROR);
            assertThat(statusOf(paused, "a")).isEqualTo(StepStatus.FAILED); // untouched by the crash
        }
    }

    @Test
    void retryingAnAgentStepWarnsWhenThePromptFileHasChangedSinceRunStart(@TempDir Path repo) throws IOException {
        Path promptDir = repo.resolve("prompts");
        Files.createDirectories(promptDir);
        Path promptFile = promptDir.resolve("work.md");
        Files.writeString(promptFile, "Original prompt");

        AgentStep agent = new AgentStep("work", List.of(), "claude", Path.of("prompts/work.md"),
                List.of(), List.of(), List.of(), RetryPolicy.DEFAULT, BUDGET);
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(agent));
        InMemoryStateStore stateStore = new InMemoryStateStore();

        FixtureAgentRuntimePort agentRuntime = new FixtureAgentRuntimePort(inv ->
                new AgentResult(0, Optional.empty(), new TokenUsage(1, 1), Path.of("raw.log")));

        try (PipelineEngine engine = new PipelineEngine(stateStore, agentRuntime, FixtureScriptRunnerPort.alwaysOk())) {
            RunId runId = engine.start(TestProjects.minimal(repo), definition, "feature-x");

            // Default policy auto-retries a STREAM failure once; wait for the terminal FAILED
            // that follows the *second* failure so both attempts (and the edit below) have run.
            until(() -> engine.snapshot(runId).map(s -> statusOf(s, "work") == StepStatus.FAILED).orElse(false));
            Files.writeString(promptFile, "Edited prompt, mid-run");

            engine.submit(new EngineCommand.RetryStep(runId, "work"));
            until(() -> stateStore.audit().stream().anyMatch(e -> e.type().equals("prompt.drift")));

            AuditEvent drift = stateStore.audit().stream()
                    .filter(e -> e.type().equals("prompt.drift")).findFirst().orElseThrow();
            assertThat(drift.stepId()).isEqualTo("work");
            assertThat(drift.payload().get("diff").asText()).contains("Edited prompt");
        }
    }
}
