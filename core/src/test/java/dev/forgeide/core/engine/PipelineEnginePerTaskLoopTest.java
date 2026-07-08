package dev.forgeide.core.engine;

import dev.forgeide.core.engine.support.FixtureAgentRuntimePort;
import dev.forgeide.core.engine.support.FixtureScriptRunnerPort;
import dev.forgeide.core.engine.support.InMemoryStateStore;
import dev.forgeide.core.engine.support.TestProjects;
import dev.forgeide.core.pipeline.PerTaskLoop;
import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.ScriptStep;
import dev.forgeide.core.port.ScriptResult;
import dev.forgeide.core.run.RunId;
import dev.forgeide.core.run.RunSnapshot;
import dev.forgeide.core.run.RunStatus;
import dev.forgeide.core.run.StepSnapshot;
import dev.forgeide.core.run.StepStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static dev.forgeide.core.engine.support.Await.until;
import static dev.forgeide.core.engine.support.Snapshots.statusOf;
import static org.assertj.core.api.Assertions.assertThat;

/** T06 scope: {@code per_task_loop} unrolls one subgraph per task-plan.json entry. */
class PipelineEnginePerTaskLoopTest {

    @Test
    void expandsOneStepInstancePerTaskPlanEntryAndCompletesTheRun(@TempDir Path repo) throws IOException {
        Files.writeString(repo.resolve("task-plan.json"), "[{\"id\":\"t1\"}, {\"id\":\"t2\"}, \"t3\"]");

        ScriptStep source = new ScriptStep("source", List.of(), List.of("ground"), Duration.ofSeconds(5));
        ScriptStep implTemplate = new ScriptStep("impl", List.of(), List.of("run-impl"), Duration.ofSeconds(5));
        PerTaskLoop fanout = new PerTaskLoop("fanout", List.of("source"), Path.of("task-plan.json"),
                List.of(implTemplate));
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(source, fanout));

        FixtureScriptRunnerPort scriptRunner = new FixtureScriptRunnerPort(inv -> new ScriptResult(0, "ok", ""));
        FixtureAgentRuntimePort agentRuntime = new FixtureAgentRuntimePort(inv -> {
            throw new AssertionError("unused in this test");
        });

        try (PipelineEngine engine = new PipelineEngine(new InMemoryStateStore(), agentRuntime, scriptRunner)) {
            RunId runId = engine.start(TestProjects.minimal(repo), definition, "feature-x");

            until(() -> engine.snapshot(runId).map(s -> s.status() == RunStatus.COMPLETED).orElse(false), 5_000);

            RunSnapshot snapshot = engine.snapshot(runId).orElseThrow();
            assertThat(snapshot.steps()).extracting(StepSnapshot::stepId).contains(
                    "source", "fanout", "fanout/t1/impl", "fanout/t2/impl", "fanout/t3/impl");
            assertThat(statusOf(snapshot, "fanout")).isEqualTo(StepStatus.PASSED);
            assertThat(statusOf(snapshot, "fanout/t1/impl")).isEqualTo(StepStatus.PASSED);
            assertThat(statusOf(snapshot, "fanout/t2/impl")).isEqualTo(StepStatus.PASSED);
            assertThat(statusOf(snapshot, "fanout/t3/impl")).isEqualTo(StepStatus.PASSED);
        }
    }
}
