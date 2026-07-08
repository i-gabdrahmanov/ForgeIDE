package dev.forgeide.core.engine;

import dev.forgeide.core.engine.support.FixtureAgentRuntimePort;
import dev.forgeide.core.engine.support.FixtureScriptRunnerPort;
import dev.forgeide.core.engine.support.InMemoryStateStore;
import dev.forgeide.core.engine.support.TestProjects;
import dev.forgeide.core.event.EngineCommand;
import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.ScriptStep;
import dev.forgeide.core.port.ScriptResult;
import dev.forgeide.core.run.RunId;
import dev.forgeide.core.run.RunSnapshot;
import dev.forgeide.core.run.RunStatus;
import dev.forgeide.core.run.StepStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.forgeide.core.engine.support.Await.until;
import static dev.forgeide.core.engine.support.Snapshots.statusOf;
import static org.assertj.core.api.Assertions.assertThat;

/** T06 acceptance: a pipeline of script steps runs end-to-end on a fixture {@code ScriptRunnerPort}. */
class PipelineEngineScriptPipelineTest {

    @Test
    void linearScriptPipelineCompletesEndToEnd(@TempDir Path repo) {
        ScriptStep a = new ScriptStep("a", List.of(), List.of("build"), Duration.ofSeconds(5));
        ScriptStep b = new ScriptStep("b", List.of("a"), List.of("test"), Duration.ofSeconds(5));
        ScriptStep c = new ScriptStep("c", List.of("b"), List.of("deploy"), Duration.ofSeconds(5));
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(a, b, c));

        InMemoryStateStore stateStore = new InMemoryStateStore();
        FixtureScriptRunnerPort scriptRunner = FixtureScriptRunnerPort.alwaysOk();
        FixtureAgentRuntimePort agentRuntime = new FixtureAgentRuntimePort(inv -> {
            throw new AssertionError("a script-only pipeline must never call the agent runtime");
        });

        try (PipelineEngine engine = new PipelineEngine(stateStore, agentRuntime, scriptRunner)) {
            RunId runId = engine.start(TestProjects.minimal(repo), definition, "feature-x");

            until(() -> engine.snapshot(runId).map(s -> s.status() == RunStatus.COMPLETED).orElse(false));

            RunSnapshot snapshot = engine.snapshot(runId).orElseThrow();
            assertThat(snapshot.steps()).hasSize(3);
            assertThat(snapshot.steps()).allMatch(s -> s.status() == StepStatus.PASSED);
            assertThat(stateStore.load(runId)).isPresent();
            assertThat(stateStore.load(runId).orElseThrow().status()).isEqualTo(RunStatus.COMPLETED);
        }
    }

    @Test
    void aFailingScriptStepBlocksTheRunUntilRetried(@TempDir Path repo) {
        ScriptStep a = new ScriptStep("a", List.of(), List.of("flaky"), Duration.ofSeconds(5));
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(a));

        AtomicInteger calls = new AtomicInteger();
        FixtureScriptRunnerPort scriptRunner = new FixtureScriptRunnerPort(inv ->
                calls.incrementAndGet() == 1
                        ? new ScriptResult(1, "", "boom")
                        : new ScriptResult(0, "ok", ""));
        FixtureAgentRuntimePort agentRuntime = new FixtureAgentRuntimePort(inv -> {
            throw new AssertionError("unused in this test");
        });
        InMemoryStateStore stateStore = new InMemoryStateStore();

        try (PipelineEngine engine = new PipelineEngine(stateStore, agentRuntime, scriptRunner)) {
            RunId runId = engine.start(TestProjects.minimal(repo), definition, "feature-x");

            until(() -> engine.snapshot(runId).map(s -> statusOf(s, "a") == StepStatus.FAILED).orElse(false));
            assertThat(engine.snapshot(runId).orElseThrow().status()).isEqualTo(RunStatus.RUNNING);

            engine.submit(new EngineCommand.RetryStep(runId, "a"));

            until(() -> engine.snapshot(runId).map(s -> s.status() == RunStatus.COMPLETED).orElse(false));
            assertThat(statusOf(engine.snapshot(runId).orElseThrow(), "a")).isEqualTo(StepStatus.PASSED);
        }
    }
}
