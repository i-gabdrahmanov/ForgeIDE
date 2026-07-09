package dev.forgeide.core.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.forgeide.core.audit.AuditEvent;
import dev.forgeide.core.engine.support.FixtureAgentRuntimePort;
import dev.forgeide.core.engine.support.FixtureScriptRunnerPort;
import dev.forgeide.core.engine.support.InMemoryStateStore;
import dev.forgeide.core.engine.support.TestProjects;
import dev.forgeide.core.event.EngineCommand;
import dev.forgeide.core.pipeline.AgentStep;
import dev.forgeide.core.pipeline.PerTaskLoop;
import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.ScriptStep;
import dev.forgeide.core.policy.RetryPolicy;
import dev.forgeide.core.policy.TokenBudget;
import dev.forgeide.core.port.AgentResult;
import dev.forgeide.core.port.ScriptResult;
import dev.forgeide.core.port.TokenUsage;
import dev.forgeide.core.run.FailureReason;
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
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static dev.forgeide.core.engine.support.Await.until;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * T11 acceptance (SDD FR-3.4): a run persisted by an earlier, now-dead process can be rehydrated
 * into a fresh {@link PipelineEngine} and picked back up from where it was left, including a
 * {@code per_task_loop}'s runtime-unrolled steps and a retried agent step's judge-accumulated
 * context — neither of which the static {@link PipelineDefinition} carries on its own.
 */
class PipelineEngineResumeTest {

    private static final TokenBudget BUDGET = new TokenBudget(1_000, Duration.ofMinutes(5), 10);

    private static FixtureAgentRuntimePort throwingAgent() {
        return new FixtureAgentRuntimePort(inv -> {
            throw new AssertionError("unused in this test");
        });
    }

    @Test
    void resumeRehydratesAnAbandonedRunSoItsInterruptedStepCanBeRetried(@TempDir Path repo) {
        ScriptStep a = new ScriptStep("a", List.of(), List.of("build"), Duration.ofSeconds(5));
        ScriptStep b = new ScriptStep("b", List.of("a"), List.of("test"), Duration.ofSeconds(5));
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(a, b));

        InMemoryStateStore stateStore = new InMemoryStateStore();
        RunId runId = RunId.newId();
        StepSnapshot passedA = new StepSnapshot("a", StepStatus.PASSED, 1, Optional.empty(), List.of(), List.of(), List.of());
        StepSnapshot interruptedB = new StepSnapshot("b", StepStatus.FAILED, 1,
                Optional.of(FailureReason.INTERRUPTED), List.of(), List.of(), List.of());
        stateStore.save(new RunSnapshot(runId, "feature-x", RunStatus.RUNNING, Optional.empty(),
                List.of(passedA, interruptedB)));

        try (PipelineEngine engine = new PipelineEngine(stateStore, throwingAgent(), FixtureScriptRunnerPort.alwaysOk())) {
            engine.resume(TestProjects.minimal(repo), definition, runId);
            until(() -> engine.snapshot(runId).isPresent());
            assertThat(stateStore.audit().stream().anyMatch(e -> e.type().equals("run.resumed"))).isTrue();

            engine.submit(new EngineCommand.RetryStep(runId, "b"));

            until(() -> engine.snapshot(runId).map(s -> s.status() == RunStatus.COMPLETED).orElse(false));
        }
    }

    @Test
    void resumeIsANoOpForARunAlreadyLiveInThisEngine(@TempDir Path repo) {
        ScriptStep a = new ScriptStep("a", List.of(), List.of("build"), Duration.ofSeconds(5));
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(a));
        InMemoryStateStore stateStore = new InMemoryStateStore();

        try (PipelineEngine engine = new PipelineEngine(stateStore, throwingAgent(), FixtureScriptRunnerPort.alwaysOk())) {
            RunId runId = engine.start(TestProjects.minimal(repo), definition, "feature-x");
            until(() -> engine.snapshot(runId).map(s -> s.status() == RunStatus.COMPLETED).orElse(false));

            engine.resume(TestProjects.minimal(repo), definition, runId);

            // The actor processes its mailbox FIFO; a second run started afterwards only
            // completes once the resume(already-live) call ahead of it has been drained.
            RunId other = engine.start(TestProjects.minimal(repo), definition, "feature-y");
            until(() -> engine.snapshot(other).map(s -> s.status() == RunStatus.COMPLETED).orElse(false));

            assertThat(stateStore.audit().stream().anyMatch(e -> e.type().equals("run.resumed"))).isFalse();
        }
    }

    @Test
    void resumeReExpandsAnAlreadyPassedPerTaskLoopSoAnInterruptedChildCanBeRetried(@TempDir Path repo)
            throws IOException {
        Files.writeString(repo.resolve("task-plan.json"), "[\"t1\"]");
        ScriptStep subTemplate = new ScriptStep("sub", List.of(), List.of("run-sub"), Duration.ofSeconds(5));
        PerTaskLoop fanout = new PerTaskLoop("fanout", List.of(), Path.of("task-plan.json"), List.of(subTemplate));
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(fanout));

        InMemoryStateStore stateStore = new InMemoryStateStore();
        RunId runId = RunId.newId();
        StepSnapshot loopSnap = new StepSnapshot("fanout", StepStatus.PASSED, 1, Optional.empty(),
                List.of(), List.of(), List.of());
        StepSnapshot childSnap = new StepSnapshot("fanout/t1/sub", StepStatus.FAILED, 1,
                Optional.of(FailureReason.INTERRUPTED), List.of(), List.of(), List.of());
        stateStore.save(new RunSnapshot(runId, "feature-x", RunStatus.RUNNING, Optional.empty(),
                List.of(loopSnap, childSnap)));

        try (PipelineEngine engine = new PipelineEngine(stateStore, throwingAgent(), FixtureScriptRunnerPort.alwaysOk())) {
            engine.resume(TestProjects.minimal(repo), definition, runId);
            until(() -> engine.snapshot(runId).isPresent());

            // Without re-expanding the loop, ctx.stepDefs has no entry for the namespaced child
            // and dispatch(ctx, null) blows up — the generic actor-exception catch would then
            // pause the run instead of completing it.
            engine.submit(new EngineCommand.RetryStep(runId, "fanout/t1/sub"));

            until(() -> engine.snapshot(runId).map(s -> s.status() == RunStatus.COMPLETED).orElse(false));
        }
    }

    @Test
    void resumeReplaysAccumulatedJudgeErrorsIntoTheRetriedAgentsPrompt(@TempDir Path repo) throws IOException {
        Path promptDir = repo.resolve("prompts");
        Files.createDirectories(promptDir);
        Files.writeString(promptDir.resolve("work.md"), "Do the thing");

        AgentStep work = new AgentStep("work", List.of(), "claude", Path.of("prompts/work.md"),
                List.of(), List.of(), List.of(), RetryPolicy.DEFAULT, BUDGET);
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(work));

        InMemoryStateStore stateStore = new InMemoryStateStore();
        RunId runId = RunId.newId();
        // iteration 2 was RUNNING when the process died; iteration 1 had already collected a
        // judge failure that must still shape the retried prompt.
        StepSnapshot workSnap = new StepSnapshot("work", StepStatus.FAILED, 2,
                Optional.of(FailureReason.INTERRUPTED), List.of(), List.of(), List.of());
        stateStore.save(new RunSnapshot(runId, "feature-x", RunStatus.RUNNING, Optional.empty(), List.of(workSnap)));

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode verdictPayload = mapper.createObjectNode();
        verdictPayload.put("targetStepId", "work");
        verdictPayload.put("passed", false);
        verdictPayload.put("detail", "needs more tests");
        stateStore.appendAudit(new AuditEvent(0, Instant.now(), runId, "review", 1, "judge.verdict",
                verdictPayload, "", ""));

        List<String> seenPrompts = new CopyOnWriteArrayList<>();
        FixtureAgentRuntimePort agentRuntime = new FixtureAgentRuntimePort(inv -> {
            seenPrompts.add(inv.prompt());
            ObjectNode json = mapper.createObjectNode();
            json.put("step_id", "work");
            return new AgentResult(0, Optional.of(json), new TokenUsage(1, 1), Path.of("raw.log"));
        });

        try (PipelineEngine engine = new PipelineEngine(stateStore, agentRuntime, FixtureScriptRunnerPort.alwaysOk())) {
            engine.resume(TestProjects.minimal(repo), definition, runId);
            until(() -> engine.snapshot(runId).isPresent());

            engine.submit(new EngineCommand.RetryStep(runId, "work"));

            until(() -> engine.snapshot(runId).map(s -> s.status() == RunStatus.COMPLETED).orElse(false));
            assertThat(seenPrompts).hasSize(1);
            assertThat(seenPrompts.get(0)).contains("needs more tests");
        }
    }

    @Test
    void resumeOfAnUnknownRunIdIsIgnored(@TempDir Path repo) {
        ScriptStep a = new ScriptStep("a", List.of(), List.of("build"), Duration.ofSeconds(5));
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(a));
        InMemoryStateStore stateStore = new InMemoryStateStore();
        RunId runId = RunId.newId();

        try (PipelineEngine engine = new PipelineEngine(stateStore, throwingAgent(), FixtureScriptRunnerPort.alwaysOk())) {
            engine.resume(TestProjects.minimal(repo), definition, runId);

            // The actor processes its mailbox FIFO; once a run started afterwards completes, the
            // resume(unknown) call ahead of it has necessarily already been drained (and ignored).
            RunId other = engine.start(TestProjects.minimal(repo), definition, "feature-y");
            until(() -> engine.snapshot(other).map(s -> s.status() == RunStatus.COMPLETED).orElse(false));

            assertThat(engine.snapshot(runId)).isEmpty();
        }
    }
}
