package dev.forgeide.core.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.forgeide.core.engine.support.FixtureAgentRuntimePort;
import dev.forgeide.core.engine.support.FixtureScriptRunnerPort;
import dev.forgeide.core.engine.support.InMemoryStateStore;
import dev.forgeide.core.engine.support.TestProjects;
import dev.forgeide.core.event.EngineCommand;
import dev.forgeide.core.pipeline.AgentStep;
import dev.forgeide.core.pipeline.BranchStep;
import dev.forgeide.core.pipeline.GateStep;
import dev.forgeide.core.pipeline.JudgeStep;
import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.ScriptStep;
import dev.forgeide.core.policy.FailPolicy;
import dev.forgeide.core.policy.RetryPolicy;
import dev.forgeide.core.policy.TokenBudget;
import dev.forgeide.core.port.AgentResult;
import dev.forgeide.core.port.ScriptResult;
import dev.forgeide.core.port.TokenUsage;
import dev.forgeide.core.run.FailureReason;
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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.forgeide.core.engine.support.Await.until;
import static dev.forgeide.core.engine.support.Snapshots.statusOf;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-style coverage of T06's state machine: every {@code StepStatus} is reachable on
 * fixture executors, and commands that would produce an illegal transition are no-ops rather
 * than corrupting the run.
 */
class PipelineEngineTransitionsTest {

    private static final TokenBudget BUDGET = new TokenBudget(1_000, Duration.ofMinutes(5), 10);

    @Test
    void gateWaitsThenBranchRoutesForwardAndSkipsTheLosingTarget(@TempDir Path repo) {
        GateStep gate = new GateStep("gate", List.of(), "Ship it?", List.of("approve", "reject"), List.of());
        BranchStep route = new BranchStep("route", List.of("gate"),
                Map.of("approve", "on-approve", "reject", "on-reject"));
        ScriptStep onApprove = new ScriptStep("on-approve", List.of("route"), List.of("ship"), Duration.ofSeconds(5));
        ScriptStep onReject = new ScriptStep("on-reject", List.of("route"), List.of("rework"), Duration.ofSeconds(5));
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(gate, route, onApprove, onReject));

        try (PipelineEngine engine = engine(FixtureScriptRunnerPort.alwaysOk(), throwingAgent())) {
            RunId runId = engine.start(TestProjects.minimal(repo), definition, "feature-x");

            until(() -> engine.snapshot(runId).map(s -> statusOf(s, "gate") == StepStatus.WAITING_GATE).orElse(false));

            engine.submit(new EngineCommand.GateAnswered(runId, "gate", "approve", "tester", Instant.now()));

            until(() -> engine.snapshot(runId).map(s -> s.status() == RunStatus.COMPLETED).orElse(false));
            RunSnapshot snapshot = engine.snapshot(runId).orElseThrow();
            assertThat(statusOf(snapshot, "gate")).isEqualTo(StepStatus.PASSED);
            assertThat(statusOf(snapshot, "route")).isEqualTo(StepStatus.PASSED);
            assertThat(statusOf(snapshot, "on-approve")).isEqualTo(StepStatus.PASSED);
            assertThat(statusOf(snapshot, "on-reject")).isEqualTo(StepStatus.SKIPPED);
        }
    }

    @Test
    void r2RiskGateRejectsConfirmationWithoutTheDiffAckCheckbox(@TempDir Path repo) {
        GateStep gate = new GateStep("gate", List.of(), "Ship it?", List.of("approve", "reject"), List.of(),
                dev.forgeide.core.project.RiskLevel.R2);
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(gate));

        try (PipelineEngine engine = engine(FixtureScriptRunnerPort.alwaysOk(), throwingAgent())) {
            RunId runId = engine.start(TestProjects.minimal(repo), definition, "feature-x");
            until(() -> engine.snapshot(runId).map(s -> statusOf(s, "gate") == StepStatus.WAITING_GATE).orElse(false));

            // diffAcked defaults to false via the terse constructor — must be refused (FR-5.3).
            engine.submit(new EngineCommand.GateAnswered(runId, "gate", "approve", "tester", Instant.now()));
            assertThat(statusOf(engine.snapshot(runId).orElseThrow(), "gate")).isEqualTo(StepStatus.WAITING_GATE);

            engine.submit(new EngineCommand.GateAnswered(runId, "gate", "approve", "tester", Instant.now(),
                    Optional.empty(), true));

            until(() -> engine.snapshot(runId).map(s -> s.status() == RunStatus.COMPLETED).orElse(false));
            assertThat(statusOf(engine.snapshot(runId).orElseThrow(), "gate")).isEqualTo(StepStatus.PASSED);
        }
    }

    @Test
    void gateAnswerOutsideItsOptionsIsIgnored(@TempDir Path repo) {
        GateStep gate = new GateStep("gate", List.of(), "Ship it?", List.of("approve", "reject"), List.of());
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(gate));

        try (PipelineEngine engine = engine(FixtureScriptRunnerPort.alwaysOk(), throwingAgent())) {
            RunId runId = engine.start(TestProjects.minimal(repo), definition, "feature-x");
            until(() -> engine.snapshot(runId).map(s -> statusOf(s, "gate") == StepStatus.WAITING_GATE).orElse(false));

            engine.submit(new EngineCommand.GateAnswered(runId, "gate", "maybe", "tester", Instant.now()));
            // Proven by a subsequent valid answer still working correctly: the bogus one
            // must not have corrupted the gate's WAITING_GATE state.
            engine.submit(new EngineCommand.GateAnswered(runId, "gate", "approve", "tester", Instant.now()));

            until(() -> engine.snapshot(runId).map(s -> s.status() == RunStatus.COMPLETED).orElse(false));
            assertThat(statusOf(engine.snapshot(runId).orElseThrow(), "gate")).isEqualTo(StepStatus.PASSED);
        }
    }

    @Test
    void retryStepOnANonFailedStepIsIgnored(@TempDir Path repo) {
        ScriptStep a = new ScriptStep("a", List.of(), List.of("build"), Duration.ofSeconds(5));
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(a));

        try (PipelineEngine engine = engine(FixtureScriptRunnerPort.alwaysOk(), throwingAgent())) {
            RunId runId = engine.start(TestProjects.minimal(repo), definition, "feature-x");
            until(() -> engine.snapshot(runId).map(s -> s.status() == RunStatus.COMPLETED).orElse(false));

            // "a" is already PASSED; retrying it must be a no-op, not a resurrection.
            engine.submit(new EngineCommand.RetryStep(runId, "a"));

            assertThat(statusOf(engine.snapshot(runId).orElseThrow(), "a")).isEqualTo(StepStatus.PASSED);
            assertThat(engine.snapshot(runId).orElseThrow().status()).isEqualTo(RunStatus.COMPLETED);
        }
    }

    @Test
    void judgeFailReRunsTheTargetWithAccumulatedErrorsUntilItPasses(@TempDir Path repo) {
        ScriptStep work = new ScriptStep("work", List.of(), List.of("run-target"), Duration.ofSeconds(5));
        ScriptStep check = new ScriptStep("review.check", List.of(), List.of("check-target"), Duration.ofSeconds(5));
        JudgeStep review = new JudgeStep("review", List.of("work"), "work",
                Optional.empty(), Optional.of(check), FailPolicy.DEFAULT);
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(work, review));

        AtomicInteger checkCalls = new AtomicInteger();
        AtomicInteger workRuns = new AtomicInteger();
        FixtureScriptRunnerPort scriptRunner = new FixtureScriptRunnerPort(inv -> {
            if (inv.command().equals(List.of("run-target"))) {
                workRuns.incrementAndGet();
                return new ScriptResult(0, "ok", "");
            }
            int n = checkCalls.incrementAndGet();
            return n < 3 ? new ScriptResult(1, "", "check failed #" + n) : new ScriptResult(0, "ok", "");
        });

        try (PipelineEngine engine = engine(scriptRunner, throwingAgent())) {
            RunId runId = engine.start(TestProjects.minimal(repo), definition, "feature-x");

            until(() -> engine.snapshot(runId).map(s -> s.status() == RunStatus.COMPLETED).orElse(false), 5_000);

            RunSnapshot snapshot = engine.snapshot(runId).orElseThrow();
            assertThat(statusOf(snapshot, "work")).isEqualTo(StepStatus.PASSED);
            assertThat(statusOf(snapshot, "review")).isEqualTo(StepStatus.PASSED);
            assertThat(workRuns.get()).isEqualTo(3);
            assertThat(checkCalls.get()).isEqualTo(3);
            assertThat(snapshot.steps().stream().filter(s -> s.stepId().equals("review")).findFirst().orElseThrow()
                    .verdicts()).hasSize(3);
        }
    }

    @Test
    void judgeEscalatesToAWaitingGateAfterExhaustingFailPolicyThenCancelFailsIt(@TempDir Path repo) {
        ScriptStep work = new ScriptStep("work", List.of(), List.of("run-target"), Duration.ofSeconds(5));
        ScriptStep check = new ScriptStep("review.check", List.of(), List.of("check-target"), Duration.ofSeconds(5));
        JudgeStep review = new JudgeStep("review", List.of("work"), "work",
                Optional.empty(), Optional.of(check), new FailPolicy(1));
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(work, review));

        FixtureScriptRunnerPort scriptRunner = new FixtureScriptRunnerPort(inv ->
                inv.command().equals(List.of("run-target"))
                        ? new ScriptResult(0, "ok", "")
                        : new ScriptResult(1, "", "always fails"));

        try (PipelineEngine engine = engine(scriptRunner, throwingAgent())) {
            RunId runId = engine.start(TestProjects.minimal(repo), definition, "feature-x");

            until(() -> engine.snapshot(runId).map(s -> statusOf(s, "review") == StepStatus.WAITING_GATE).orElse(false));

            engine.submit(new EngineCommand.GateAnswered(runId, "review", "cancel", "tester", Instant.now()));

            until(() -> engine.snapshot(runId).map(s -> statusOf(s, "review") == StepStatus.FAILED).orElse(false));
            RunSnapshot snapshot = engine.snapshot(runId).orElseThrow();
            assertThat(snapshot.status()).isEqualTo(RunStatus.RUNNING); // blocked, not auto-completed
            assertThat(snapshot.steps().stream().filter(s -> s.stepId().equals("review")).findFirst().orElseThrow()
                    .failureReason()).contains(FailureReason.JUDGE);
        }
    }

    @Test
    void judgeEscalationRetryGivesOneMoreAttempt(@TempDir Path repo) {
        ScriptStep work = new ScriptStep("work", List.of(), List.of("run-target"), Duration.ofSeconds(5));
        ScriptStep check = new ScriptStep("review.check", List.of(), List.of("check-target"), Duration.ofSeconds(5));
        JudgeStep review = new JudgeStep("review", List.of("work"), "work",
                Optional.empty(), Optional.of(check), new FailPolicy(1));
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(work, review));

        AtomicInteger workRuns = new AtomicInteger();
        FixtureScriptRunnerPort scriptRunner = new FixtureScriptRunnerPort(inv -> {
            if (inv.command().equals(List.of("run-target"))) {
                workRuns.incrementAndGet();
                return new ScriptResult(0, "ok", "");
            }
            return new ScriptResult(1, "", "always fails");
        });

        try (PipelineEngine engine = engine(scriptRunner, throwingAgent())) {
            RunId runId = engine.start(TestProjects.minimal(repo), definition, "feature-x");
            until(() -> engine.snapshot(runId).map(s -> statusOf(s, "review") == StepStatus.WAITING_GATE).orElse(false));

            engine.submit(new EngineCommand.GateAnswered(runId, "review", "retry", "tester", Instant.now()));

            // One extra attempt at "work", then the check fails again and it escalates again.
            until(() -> workRuns.get() >= 2);
            until(() -> engine.snapshot(runId).map(s -> statusOf(s, "review") == StepStatus.WAITING_GATE).orElse(false));
        }
    }

    @Test
    void judgeEscalationResetChainGivesAFreshIterationBudget(@TempDir Path repo) {
        ScriptStep work = new ScriptStep("work", List.of(), List.of("run-target"), Duration.ofSeconds(5));
        ScriptStep check = new ScriptStep("review.check", List.of(), List.of("check-target"), Duration.ofSeconds(5));
        JudgeStep review = new JudgeStep("review", List.of("work"), "work",
                Optional.empty(), Optional.of(check), new FailPolicy(2));
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(work, review));

        AtomicInteger checkCalls = new AtomicInteger();
        FixtureScriptRunnerPort scriptRunner = new FixtureScriptRunnerPort(inv ->
                inv.command().equals(List.of("run-target"))
                        ? new ScriptResult(0, "ok", "")
                        : new ScriptResult(1, "", "fail #" + checkCalls.incrementAndGet()));

        try (PipelineEngine engine = engine(scriptRunner, throwingAgent())) {
            RunId runId = engine.start(TestProjects.minimal(repo), definition, "feature-x");
            until(() -> engine.snapshot(runId).map(s -> statusOf(s, "review") == StepStatus.WAITING_GATE).orElse(false));
            assertThat(checkCalls.get()).isEqualTo(2);

            engine.submit(new EngineCommand.GateAnswered(runId, "review", "reset_chain", "tester", Instant.now()));

            // A fresh 2-attempt budget needs two MORE failures, not one, before re-escalating —
            // proof the iteration counter (not just accumulated_errors) was actually reset.
            until(() -> engine.snapshot(runId).map(s -> statusOf(s, "review") == StepStatus.WAITING_GATE).orElse(false)
                    && checkCalls.get() >= 4, 5_000);
            assertThat(checkCalls.get()).isEqualTo(4);
        }
    }

    @Test
    void judgeEscalationOverrideRequiresANonBlankReasonThenForcesTargetAndDownstreamPassed(@TempDir Path repo) {
        ScriptStep work = new ScriptStep("work", List.of(), List.of("run-target"), Duration.ofSeconds(5));
        ScriptStep check = new ScriptStep("review.check", List.of(), List.of("check-target"), Duration.ofSeconds(5));
        JudgeStep review = new JudgeStep("review", List.of("work"), "work",
                Optional.empty(), Optional.of(check), new FailPolicy(1));
        ScriptStep after = new ScriptStep("after", List.of("review"), List.of("after-target"), Duration.ofSeconds(5));
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(work, review, after));

        FixtureScriptRunnerPort scriptRunner = new FixtureScriptRunnerPort(inv ->
                inv.command().equals(List.of("check-target"))
                        ? new ScriptResult(1, "", "always fails")
                        : new ScriptResult(0, "ok", ""));

        try (PipelineEngine engine = engine(scriptRunner, throwingAgent())) {
            RunId runId = engine.start(TestProjects.minimal(repo), definition, "feature-x");
            until(() -> engine.snapshot(runId).map(s -> statusOf(s, "review") == StepStatus.WAITING_GATE).orElse(false));

            engine.submit(new EngineCommand.GateAnswered(runId, "review", "override", "tester", Instant.now(),
                    Optional.of("   ")));
            assertThat(statusOf(engine.snapshot(runId).orElseThrow(), "review")).isEqualTo(StepStatus.WAITING_GATE);

            engine.submit(new EngineCommand.GateAnswered(runId, "review", "override", "tester", Instant.now(),
                    Optional.of("hotfix window, shipping now")));

            until(() -> engine.snapshot(runId).map(s -> s.status() == RunStatus.COMPLETED).orElse(false));
            RunSnapshot snapshot = engine.snapshot(runId).orElseThrow();
            assertThat(statusOf(snapshot, "review")).isEqualTo(StepStatus.PASSED);
            assertThat(statusOf(snapshot, "after")).isEqualTo(StepStatus.PASSED);
        }
    }

    @Test
    void pendingQuestionsWaitForInputThenRerunWithAnswers(@TempDir Path repo) throws IOException {
        Path promptDir = repo.resolve("prompts");
        Files.createDirectories(promptDir);
        Files.writeString(promptDir.resolve("work.md"), "Do the thing for ${feature.slug}");

        AgentStep agent = new AgentStep("work", List.of(), "claude", Path.of("prompts/work.md"),
                List.of(), List.of(), List.of(), RetryPolicy.DEFAULT, BUDGET);
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(agent));

        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger calls = new AtomicInteger();
        FixtureAgentRuntimePort agentRuntime = new FixtureAgentRuntimePort(inv -> {
            int n = calls.incrementAndGet();
            ObjectNode json = mapper.createObjectNode();
            json.put("step_id", "work");
            if (n == 1) {
                assertThat(inv.prompt()).contains("Do the thing for feature-x");
                ArrayNode questions = json.putArray("pending_questions");
                ObjectNode q = questions.addObject();
                q.put("id", "q1");
                q.put("text", "Which epic?");
                q.put("type", "text");
            } else {
                assertThat(inv.prompt()).contains("## answers").contains("q1: epic-42");
            }
            return new AgentResult(0, Optional.of(json), new TokenUsage(1, 1), Path.of("raw.log"));
        });

        try (PipelineEngine engine = engine(FixtureScriptRunnerPort.alwaysOk(), agentRuntime)) {
            RunId runId = engine.start(TestProjects.minimal(repo), definition, "feature-x");

            until(() -> engine.snapshot(runId).map(s -> statusOf(s, "work") == StepStatus.WAITING_INPUT).orElse(false));

            engine.submit(new EngineCommand.QuestionsAnswered(runId, "work", Map.of("q1", "epic-42")));

            until(() -> engine.snapshot(runId).map(s -> s.status() == RunStatus.COMPLETED).orElse(false));
            assertThat(calls.get()).isEqualTo(2);
        }
    }

    @Test
    void tokenBudgetExceededFailsStepWithBudgetReason(@TempDir Path repo) throws IOException {
        Path promptDir = repo.resolve("prompts");
        Files.createDirectories(promptDir);
        Files.writeString(promptDir.resolve("work.md"), "Do the thing");

        AgentStep agent = new AgentStep("work", List.of(), "claude", Path.of("prompts/work.md"),
                List.of(), List.of(), List.of(), RetryPolicy.DEFAULT, BUDGET);
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(agent));

        ObjectMapper mapper = new ObjectMapper();
        FixtureAgentRuntimePort agentRuntime = new FixtureAgentRuntimePort(inv -> {
            ObjectNode json = mapper.createObjectNode();
            json.put("step_id", "work");
            // BUDGET.tokens() == 1_000; report usage over that even though a result is present.
            return new AgentResult(0, Optional.of(json), new TokenUsage(800, 800), Path.of("raw.log"));
        });

        try (PipelineEngine engine = engine(FixtureScriptRunnerPort.alwaysOk(), agentRuntime)) {
            RunId runId = engine.start(TestProjects.minimal(repo), definition, "feature-x");

            until(() -> engine.snapshot(runId).map(s -> statusOf(s, "work") == StepStatus.FAILED).orElse(false));
            RunSnapshot snapshot = engine.snapshot(runId).orElseThrow();
            assertThat(snapshot.steps().stream().filter(s -> s.stepId().equals("work")).findFirst().orElseThrow()
                    .failureReason()).contains(FailureReason.BUDGET);
        }
    }

    @Test
    void missingExpectedArtifactFailsStepWithArtifactsReason(@TempDir Path repo) throws IOException {
        Path promptDir = repo.resolve("prompts");
        Files.createDirectories(promptDir);
        Files.writeString(promptDir.resolve("work.md"), "Do the thing");

        AgentStep agent = new AgentStep("work", List.of(), "claude", Path.of("prompts/work.md"),
                List.of(Path.of("out/report.md")), List.of(), List.of(), RetryPolicy.DEFAULT, BUDGET);
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(agent));

        ObjectMapper mapper = new ObjectMapper();
        FixtureAgentRuntimePort agentRuntime = new FixtureAgentRuntimePort(inv -> {
            ObjectNode json = mapper.createObjectNode();
            json.put("step_id", "work");
            return new AgentResult(0, Optional.of(json), new TokenUsage(1, 1), Path.of("raw.log"));
        });

        try (PipelineEngine engine = engine(FixtureScriptRunnerPort.alwaysOk(), agentRuntime)) {
            RunId runId = engine.start(TestProjects.minimal(repo), definition, "feature-x");

            until(() -> engine.snapshot(runId).map(s -> statusOf(s, "work") == StepStatus.FAILED).orElse(false));
            RunSnapshot snapshot = engine.snapshot(runId).orElseThrow();
            assertThat(snapshot.steps().stream().filter(s -> s.stepId().equals("work")).findFirst().orElseThrow()
                    .failureReason()).contains(FailureReason.ARTIFACTS);
        }
    }

    @Test
    void unknownRuntimeRefHaltsTheRunAsAnEngineError(@TempDir Path repo) throws IOException {
        Path promptDir = repo.resolve("prompts");
        Files.createDirectories(promptDir);
        Files.writeString(promptDir.resolve("work.md"), "Do the thing");

        AgentStep agent = new AgentStep("work", List.of(), "not-a-configured-runtime", Path.of("prompts/work.md"),
                List.of(), List.of(), List.of(), RetryPolicy.DEFAULT, BUDGET);
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(agent));

        try (PipelineEngine engine = engine(FixtureScriptRunnerPort.alwaysOk(), throwingAgent())) {
            RunId runId = engine.start(TestProjects.minimal(repo), definition, "feature-x");

            until(() -> engine.snapshot(runId).map(s -> s.status() == dev.forgeide.core.run.RunStatus.PAUSED)
                    .orElse(false));
            RunSnapshot snapshot = engine.snapshot(runId).orElseThrow();
            assertThat(snapshot.haltReason()).contains(dev.forgeide.core.run.RunHaltReason.ENGINE_ERROR);
            assertThat(statusOf(snapshot, "work")).isEqualTo(StepStatus.READY);
        }
    }

    private static PipelineEngine engine(FixtureScriptRunnerPort scriptRunner, FixtureAgentRuntimePort agentRuntime) {
        return new PipelineEngine(new InMemoryStateStore(), agentRuntime, scriptRunner);
    }

    private static FixtureAgentRuntimePort throwingAgent() {
        return new FixtureAgentRuntimePort(inv -> {
            throw new AssertionError("agent runtime should not be called in this scenario");
        });
    }
}
