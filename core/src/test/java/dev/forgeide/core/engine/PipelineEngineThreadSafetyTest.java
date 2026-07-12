package dev.forgeide.core.engine;

import dev.forgeide.core.engine.support.FixtureAgentRuntimePort;
import dev.forgeide.core.engine.support.FixtureScriptRunnerPort;
import dev.forgeide.core.engine.support.InMemoryStateStore;
import dev.forgeide.core.engine.support.TestProjects;
import dev.forgeide.core.event.EngineCommand;
import dev.forgeide.core.event.EngineEvent;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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

    /**
     * NFR-2 (SDD §6, task T31): "событие движка → снапшот доступен подписчику" ≤ 200 мс, measured
     * at this engine/viewmodel level (no JavaFX render in the picture) — from the moment the
     * fixture script signals completion to the moment a subscriber observes the resulting {@code
     * PASSED} snapshot via {@link PipelineEngine#subscribe}. {@link PipelineEngine#snapshot} is
     * updated synchronously just before {@code publish} (see {@code PipelineEngine#persistAndPublish}),
     * so a subscriber seeing the event is also proof the snapshot is already available.
     */
    @Test
    void engineEventReachesASubscriberWithAnUpdatedSnapshotWithin200Ms(@TempDir Path repo) throws InterruptedException {
        ScriptStep step = new ScriptStep("a", List.of(), List.of("build"), Duration.ofSeconds(5));
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(step));

        AtomicLong scriptReturnedAtNanos = new AtomicLong();
        FixtureScriptRunnerPort scriptRunner = new FixtureScriptRunnerPort(inv -> {
            ScriptResult result = new ScriptResult(0, "ok", "");
            scriptReturnedAtNanos.set(System.nanoTime());
            return result;
        });
        FixtureAgentRuntimePort agentRuntime = new FixtureAgentRuntimePort(inv -> {
            throw new AssertionError("this pipeline has no agent step");
        });

        AtomicLong subscriberSawAtNanos = new AtomicLong();
        CountDownLatch stepPassedSeen = new CountDownLatch(1);

        try (PipelineEngine engine = new PipelineEngine(new InMemoryStateStore(), agentRuntime, scriptRunner)) {
            engine.subscribe(event -> {
                if (event instanceof EngineEvent.RunUpdated updated
                        && statusOf(updated.snapshot(), "a") == StepStatus.PASSED) {
                    subscriberSawAtNanos.compareAndSet(0, System.nanoTime());
                    stepPassedSeen.countDown();
                }
            });

            RunId runId = engine.start(TestProjects.minimal(repo), definition, "feature-x");

            assertThat(stepPassedSeen.await(2, TimeUnit.SECONDS)).isTrue();

            long elapsedMs = (subscriberSawAtNanos.get() - scriptReturnedAtNanos.get()) / 1_000_000;
            assertThat(elapsedMs).isLessThan(200);

            // "снапшот доступен подписчику": a snapshot() read right after also reflects it.
            assertThat(statusOf(engine.snapshot(runId).orElseThrow(), "a")).isEqualTo(StepStatus.PASSED);
        }
    }
}
