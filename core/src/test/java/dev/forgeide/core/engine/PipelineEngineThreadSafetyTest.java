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
import dev.forgeide.core.run.StepStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

import static dev.forgeide.core.engine.support.Await.until;
import static dev.forgeide.core.engine.support.Snapshots.statusOf;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * T06 acceptance: {@code RunState} is only ever mutated from the actor thread, even when many
 * callers hammer {@link PipelineEngine#submit} concurrently (SD §3 — the entire determinism
 * argument rests on this).
 */
class PipelineEngineThreadSafetyTest {

    @Test
    void everyPublishedEventOriginatesFromASingleActorThread(@TempDir Path repo) throws InterruptedException {
        ScriptStep alwaysFails = new ScriptStep("a", List.of(), List.of("boom"), Duration.ofSeconds(5));
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(alwaysFails));

        FixtureScriptRunnerPort scriptRunner = new FixtureScriptRunnerPort(inv -> new ScriptResult(1, "", "boom"));
        FixtureAgentRuntimePort agentRuntime = new FixtureAgentRuntimePort(inv -> {
            throw new AssertionError("unused in this test");
        });

        Set<String> threadNames = ConcurrentHashMap.newKeySet();

        try (PipelineEngine engine = new PipelineEngine(new InMemoryStateStore(), agentRuntime, scriptRunner)) {
            engine.subscribe(event -> threadNames.add(Thread.currentThread().getName()));

            RunId runId = engine.start(TestProjects.minimal(repo), definition, "feature-x");
            until(() -> engine.snapshot(runId).map(s -> statusOf(s, "a") == StepStatus.FAILED).orElse(false));

            int submitterCount = 12;
            CountDownLatch ready = new CountDownLatch(submitterCount);
            CountDownLatch go = new CountDownLatch(1);
            List<Thread> submitters = new java.util.ArrayList<>();
            for (int i = 0; i < submitterCount; i++) {
                Thread t = new Thread(() -> {
                    ready.countDown();
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    engine.submit(new EngineCommand.RetryStep(runId, "a"));
                }, "submitter-" + i);
                submitters.add(t);
                t.start();
            }
            ready.await();
            go.countDown();
            for (Thread t : submitters) {
                t.join();
            }

            // Let the actor fully drain the burst of retries (each one fails again and may
            // legally re-arm another retry window).
            Thread.sleep(300);

            assertThat(threadNames).hasSize(1);
            assertThat(threadNames.iterator().next()).isNotIn(
                    submitters.stream().map(Thread::getName).toList());
        }
    }
}
