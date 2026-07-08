package dev.forgeide.core.engine;

import dev.forgeide.core.engine.support.FixtureAgentRuntimePort;
import dev.forgeide.core.engine.support.FixtureScriptRunnerPort;
import dev.forgeide.core.engine.support.InMemoryStateStore;
import dev.forgeide.core.engine.support.TestProjects;
import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.ScriptStep;
import dev.forgeide.core.port.ScriptResult;
import dev.forgeide.core.run.RunId;
import dev.forgeide.core.run.RunStatus;
import dev.forgeide.core.run.StepStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static dev.forgeide.core.engine.support.Await.until;
import static dev.forgeide.core.engine.support.Snapshots.statusOf;
import static org.assertj.core.api.Assertions.assertThat;

/** T06 acceptance: two parallel runs of different features do not affect each other. */
class PipelineEngineParallelRunsTest {

    @Test
    void aBlockedRunDoesNotHoldUpAnUnrelatedRun(@TempDir Path repo) throws IOException, InterruptedException {
        Path repoA = Files.createDirectories(repo.resolve("a"));
        Path repoB = Files.createDirectories(repo.resolve("b"));

        PipelineDefinition defA = new PipelineDefinition("p", 1,
                List.of(new ScriptStep("a", List.of(), List.of("run-a"), Duration.ofSeconds(30))));
        PipelineDefinition defB = new PipelineDefinition("p", 1,
                List.of(new ScriptStep("a", List.of(), List.of("run-b"), Duration.ofSeconds(30))));

        CountDownLatch releaseA = new CountDownLatch(1);
        FixtureScriptRunnerPort scriptRunner = new FixtureScriptRunnerPort(inv -> {
            if (inv.command().equals(List.of("run-a"))) {
                try {
                    releaseA.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return new ScriptResult(0, "ok", "");
        });
        FixtureAgentRuntimePort agentRuntime = new FixtureAgentRuntimePort(inv -> {
            throw new AssertionError("unused in this test");
        });

        try (PipelineEngine engine = new PipelineEngine(new InMemoryStateStore(), agentRuntime, scriptRunner)) {
            RunId runA = engine.start(TestProjects.minimal(repoA), defA, "feature-a");
            RunId runB = engine.start(TestProjects.minimal(repoB), defB, "feature-b");

            until(() -> engine.snapshot(runA).map(s -> statusOf(s, "a") == StepStatus.RUNNING).orElse(false));
            // B completes fully while A is still blocked inside its script call.
            until(() -> engine.snapshot(runB).map(s -> s.status() == RunStatus.COMPLETED).orElse(false));
            assertThat(engine.snapshot(runA).orElseThrow().status()).isEqualTo(RunStatus.RUNNING);
            assertThat(statusOf(engine.snapshot(runA).orElseThrow(), "a")).isEqualTo(StepStatus.RUNNING);

            releaseA.countDown();
            until(() -> engine.snapshot(runA).map(s -> s.status() == RunStatus.COMPLETED).orElse(false));

            assertThat(engine.snapshot(runA).orElseThrow().featureSlug()).isEqualTo("feature-a");
            assertThat(engine.snapshot(runB).orElseThrow().featureSlug()).isEqualTo("feature-b");
            assertThat(runA).isNotEqualTo(runB);
        }
    }
}
