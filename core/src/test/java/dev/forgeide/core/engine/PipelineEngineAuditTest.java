package dev.forgeide.core.engine;

import com.fasterxml.jackson.databind.JsonNode;
import dev.forgeide.core.audit.AuditEvent;
import dev.forgeide.core.engine.support.FixtureAgentRuntimePort;
import dev.forgeide.core.engine.support.FixtureScriptRunnerPort;
import dev.forgeide.core.engine.support.InMemoryStateStore;
import dev.forgeide.core.engine.support.TestProjects;
import dev.forgeide.core.event.EngineCommand;
import dev.forgeide.core.pipeline.AgentStep;
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

import static dev.forgeide.core.engine.support.Await.until;
import static dev.forgeide.core.engine.support.Snapshots.statusOf;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * T10 groundwork: {@code PipelineEngine} must write a hash-chain audit entry (via {@code
 * StateStore#appendAudit}) for every transition (SD §4: "аудит пишет только движок"), and record
 * a matching {@code AuditRef} on the step so it shows up in {@code StepSnapshot#events()}.
 */
class PipelineEngineAuditTest {

    private static final TokenBudget BUDGET = new TokenBudget(1_000, Duration.ofMinutes(5), 10);

    @Test
    void linearScriptPipelineEmitsRunAndStepLifecycleEvents(@TempDir Path repo) {
        ScriptStep a = new ScriptStep("a", List.of(), List.of("build"), Duration.ofSeconds(5));
        ScriptStep b = new ScriptStep("b", List.of("a"), List.of("test"), Duration.ofSeconds(5));
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(a, b));
        InMemoryStateStore stateStore = new InMemoryStateStore();

        try (PipelineEngine engine = new PipelineEngine(stateStore, throwingAgent(), FixtureScriptRunnerPort.alwaysOk())) {
            RunId runId = engine.start(TestProjects.minimal(repo), definition, "feature-x");

            until(() -> engine.snapshot(runId).map(s -> s.status() == RunStatus.COMPLETED).orElse(false));

            List<AuditEvent> audit = stateStore.loadAudit(runId);
            assertThat(audit).extracting(AuditEvent::type).containsSubsequence(
                    "run.started", "step.running", "step.completed", "step.running", "step.completed", "run.completed");
            // seq is a strictly increasing hash-chain position assigned by the store, not the caller.
            assertThat(audit).extracting(AuditEvent::seq).isSorted().doesNotHaveDuplicates();

            AuditEvent started = audit.get(0);
            assertThat(started.type()).isEqualTo("run.started");
            assertThat(started.stepId()).isNull();
            assertThat(started.payload().get("featureSlug").asText()).isEqualTo("feature-x");
            assertThat(started.payload().get("pipelineId").asText()).isEqualTo("p");
            assertThat(started.payload().get("stepIds")).extracting(JsonNode::asText).containsExactly("a", "b");

            RunSnapshot snapshot = engine.snapshot(runId).orElseThrow();
            var stepA = snapshot.steps().stream().filter(s -> s.stepId().equals("a")).findFirst().orElseThrow();
            assertThat(stepA.events()).extracting(e -> e.type()).contains("step.running", "step.completed");
            long completedSeq = stepA.events().stream().filter(e -> e.type().equals("step.completed"))
                    .findFirst().orElseThrow().seq();
            assertThat(audit.stream().filter(e -> e.seq() == completedSeq).findFirst().orElseThrow().stepId())
                    .isEqualTo("a");
        }
    }

    @Test
    void failingScriptStepEmitsStepFailedWithReasonAndDetail(@TempDir Path repo) {
        ScriptStep a = new ScriptStep("a", List.of(), List.of("flaky"), Duration.ofSeconds(5));
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(a));
        InMemoryStateStore stateStore = new InMemoryStateStore();
        FixtureScriptRunnerPort scriptRunner = new FixtureScriptRunnerPort(inv -> new ScriptResult(1, "", "boom"));

        try (PipelineEngine engine = new PipelineEngine(stateStore, throwingAgent(), scriptRunner)) {
            RunId runId = engine.start(TestProjects.minimal(repo), definition, "feature-x");

            until(() -> engine.snapshot(runId).map(s -> statusOf(s, "a") == StepStatus.FAILED).orElse(false));

            AuditEvent failed = stateStore.loadAudit(runId).stream()
                    .filter(e -> e.type().equals("step.failed")).findFirst().orElseThrow();
            assertThat(failed.stepId()).isEqualTo("a");
            assertThat(failed.payload().get("reason").asText()).isEqualTo("SCRIPT");
            assertThat(failed.payload().get("detail").asText()).contains("boom");
        }
    }

    @Test
    void retryStepEmitsStepRetried(@TempDir Path repo) {
        ScriptStep a = new ScriptStep("a", List.of(), List.of("flaky"), Duration.ofSeconds(5));
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(a));
        InMemoryStateStore stateStore = new InMemoryStateStore();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        FixtureScriptRunnerPort scriptRunner = new FixtureScriptRunnerPort(inv ->
                calls.incrementAndGet() == 1 ? new ScriptResult(1, "", "boom") : new ScriptResult(0, "ok", ""));

        try (PipelineEngine engine = new PipelineEngine(stateStore, throwingAgent(), scriptRunner)) {
            RunId runId = engine.start(TestProjects.minimal(repo), definition, "feature-x");
            until(() -> engine.snapshot(runId).map(s -> statusOf(s, "a") == StepStatus.FAILED).orElse(false));

            engine.submit(new EngineCommand.RetryStep(runId, "a"));

            until(() -> engine.snapshot(runId).map(s -> s.status() == RunStatus.COMPLETED).orElse(false));
            assertThat(stateStore.loadAudit(runId)).extracting(AuditEvent::type).contains("step.retried");
        }
    }

    @Test
    void gateEmitsGateRequestedThenGateAnswered(@TempDir Path repo) {
        GateStep gate = new GateStep("gate", List.of(), "Ship it?", List.of("approve", "reject"), List.of());
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(gate));
        InMemoryStateStore stateStore = new InMemoryStateStore();

        try (PipelineEngine engine = new PipelineEngine(stateStore, throwingAgent(), FixtureScriptRunnerPort.alwaysOk())) {
            RunId runId = engine.start(TestProjects.minimal(repo), definition, "feature-x");
            until(() -> engine.snapshot(runId).map(s -> statusOf(s, "gate") == StepStatus.WAITING_GATE).orElse(false));

            AuditEvent requested = stateStore.loadAudit(runId).stream()
                    .filter(e -> e.type().equals("gate.requested")).findFirst().orElseThrow();
            assertThat(requested.stepId()).isEqualTo("gate");
            assertThat(requested.payload().get("question").asText()).isEqualTo("Ship it?");

            Instant answeredAt = Instant.now();
            engine.submit(new EngineCommand.GateAnswered(runId, "gate", "approve", "tester", answeredAt));

            until(() -> engine.snapshot(runId).map(s -> s.status() == RunStatus.COMPLETED).orElse(false));
            AuditEvent answered = stateStore.loadAudit(runId).stream()
                    .filter(e -> e.type().equals("gate.answered")).findFirst().orElseThrow();
            assertThat(answered.payload().get("answer").asText()).isEqualTo("approve");
            assertThat(answered.payload().get("user").asText()).isEqualTo("tester");
        }
    }

    @Test
    void judgeEscalationEmitsVerdictThenGateRequestedThenGateAnswered(@TempDir Path repo) {
        ScriptStep work = new ScriptStep("work", List.of(), List.of("run-target"), Duration.ofSeconds(5));
        ScriptStep check = new ScriptStep("review.check", List.of(), List.of("check-target"), Duration.ofSeconds(5));
        JudgeStep review = new JudgeStep("review", List.of("work"), "work",
                Optional.empty(), Optional.of(check), new FailPolicy(1));
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(work, review));
        InMemoryStateStore stateStore = new InMemoryStateStore();

        FixtureScriptRunnerPort scriptRunner = new FixtureScriptRunnerPort(inv ->
                inv.command().equals(List.of("run-target"))
                        ? new ScriptResult(0, "ok", "")
                        : new ScriptResult(1, "", "always fails"));

        try (PipelineEngine engine = new PipelineEngine(stateStore, throwingAgent(), scriptRunner)) {
            RunId runId = engine.start(TestProjects.minimal(repo), definition, "feature-x");
            until(() -> engine.snapshot(runId).map(s -> statusOf(s, "review") == StepStatus.WAITING_GATE).orElse(false));

            AuditEvent verdict = stateStore.loadAudit(runId).stream()
                    .filter(e -> e.type().equals("judge.verdict")).findFirst().orElseThrow();
            assertThat(verdict.stepId()).isEqualTo("review");
            assertThat(verdict.payload().get("targetStepId").asText()).isEqualTo("work");
            assertThat(verdict.payload().get("passed").asBoolean()).isFalse();

            AuditEvent requested = stateStore.loadAudit(runId).stream()
                    .filter(e -> e.type().equals("gate.requested")).findFirst().orElseThrow();
            assertThat(requested.payload().get("targetStepId").asText()).isEqualTo("work");

            engine.submit(new EngineCommand.GateAnswered(runId, "review", "cancel", "tester", Instant.now()));

            until(() -> engine.snapshot(runId).map(s -> statusOf(s, "review") == StepStatus.FAILED).orElse(false));
            AuditEvent answered = stateStore.loadAudit(runId).stream()
                    .filter(e -> e.type().equals("gate.answered")).findFirst().orElseThrow();
            assertThat(answered.payload().get("answer").asText()).isEqualTo("cancel");
        }
    }

    @Test
    void judgeOverrideRecordsAMandatoryReasonVisibleInTheAudit(@TempDir Path repo) {
        ScriptStep work = new ScriptStep("work", List.of(), List.of("run-target"), Duration.ofSeconds(5));
        ScriptStep check = new ScriptStep("review.check", List.of(), List.of("check-target"), Duration.ofSeconds(5));
        JudgeStep review = new JudgeStep("review", List.of("work"), "work",
                Optional.empty(), Optional.of(check), new FailPolicy(1));
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(work, review));
        InMemoryStateStore stateStore = new InMemoryStateStore();

        FixtureScriptRunnerPort scriptRunner = new FixtureScriptRunnerPort(inv ->
                inv.command().equals(List.of("run-target"))
                        ? new ScriptResult(0, "ok", "")
                        : new ScriptResult(1, "", "always fails"));

        try (PipelineEngine engine = new PipelineEngine(stateStore, throwingAgent(), scriptRunner)) {
            RunId runId = engine.start(TestProjects.minimal(repo), definition, "feature-x");
            until(() -> engine.snapshot(runId).map(s -> statusOf(s, "review") == StepStatus.WAITING_GATE).orElse(false));

            // Blank reason must be refused — no override audit entry, step still WAITING_GATE.
            engine.submit(new EngineCommand.GateAnswered(runId, "review", "override", "tester", Instant.now(),
                    Optional.of("")));
            assertThat(statusOf(engine.snapshot(runId).orElseThrow(), "review")).isEqualTo(StepStatus.WAITING_GATE);
            assertThat(stateStore.loadAudit(runId)).extracting(AuditEvent::type).doesNotContain("judge.overridden");

            engine.submit(new EngineCommand.GateAnswered(runId, "review", "override", "tester", Instant.now(),
                    Optional.of("hotfix window, shipping now")));

            until(() -> engine.snapshot(runId).map(s -> statusOf(s, "review") == StepStatus.PASSED).orElse(false));
            AuditEvent overridden = stateStore.loadAudit(runId).stream()
                    .filter(e -> e.type().equals("judge.overridden")).findFirst().orElseThrow();
            assertThat(overridden.stepId()).isEqualTo("work");
            assertThat(overridden.payload().get("reason").asText()).isEqualTo("hotfix window, shipping now");
            assertThat(overridden.payload().get("judgeStepId").asText()).isEqualTo("review");

            AuditEvent answered = stateStore.loadAudit(runId).stream()
                    .filter(e -> e.type().equals("gate.answered") && e.payload().has("detail"))
                    .findFirst().orElseThrow();
            assertThat(answered.payload().get("detail").asText()).isEqualTo("hotfix window, shipping now");
        }
    }

    @Test
    void pendingQuestionsEmitAskedThenAnswered(@TempDir Path repo) throws IOException {
        Path promptDir = repo.resolve("prompts");
        Files.createDirectories(promptDir);
        Files.writeString(promptDir.resolve("work.md"), "Do the thing");

        AgentStep agent = new AgentStep("work", List.of(), "claude", Path.of("prompts/work.md"),
                List.of(), List.of(), List.of(), RetryPolicy.DEFAULT, BUDGET);
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(agent));
        InMemoryStateStore stateStore = new InMemoryStateStore();

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        FixtureAgentRuntimePort agentRuntime = new FixtureAgentRuntimePort(inv -> {
            int n = calls.incrementAndGet();
            var json = mapper.createObjectNode();
            json.put("step_id", "work");
            if (n == 1) {
                var questions = json.putArray("pending_questions");
                var q = questions.addObject();
                q.put("id", "q1");
                q.put("text", "Which epic?");
                q.put("type", "text");
            }
            return new AgentResult(0, Optional.of(json), new TokenUsage(1, 1), Path.of("raw.log"));
        });

        try (PipelineEngine engine = new PipelineEngine(stateStore, agentRuntime, FixtureScriptRunnerPort.alwaysOk())) {
            RunId runId = engine.start(TestProjects.minimal(repo), definition, "feature-x");
            until(() -> engine.snapshot(runId).map(s -> statusOf(s, "work") == StepStatus.WAITING_INPUT).orElse(false));

            AuditEvent asked = stateStore.loadAudit(runId).stream()
                    .filter(e -> e.type().equals("question.asked")).findFirst().orElseThrow();
            assertThat(asked.payload().get("questions").get(0).get("id").asText()).isEqualTo("q1");
            assertThat(asked.payload().get("questions").get(0).get("type").asText()).isEqualTo("TEXT");

            engine.submit(new EngineCommand.QuestionsAnswered(runId, "work", Map.of("q1", "epic-42"),
                    "tester", Instant.now()));

            until(() -> engine.snapshot(runId).map(s -> s.status() == RunStatus.COMPLETED).orElse(false));
            AuditEvent answered = stateStore.loadAudit(runId).stream()
                    .filter(e -> e.type().equals("question.answered")).findFirst().orElseThrow();
            assertThat(answered.payload().get("answers").get("q1").asText()).isEqualTo("epic-42");
            assertThat(answered.payload().get("user").asText()).isEqualTo("tester");
            assertThat(answered.payload().hasNonNull("at")).isTrue();
        }
    }

    @Test
    void cancelRunEmitsRunCancelled(@TempDir Path repo) {
        ScriptStep a = new ScriptStep("a", List.of(), List.of("build"), Duration.ofSeconds(5));
        GateStep gate = new GateStep("gate", List.of("a"), "Ship?", List.of("yes"), List.of());
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(a, gate));
        InMemoryStateStore stateStore = new InMemoryStateStore();

        try (PipelineEngine engine = new PipelineEngine(stateStore, throwingAgent(), FixtureScriptRunnerPort.alwaysOk())) {
            RunId runId = engine.start(TestProjects.minimal(repo), definition, "feature-x");
            until(() -> engine.snapshot(runId).map(s -> statusOf(s, "gate") == StepStatus.WAITING_GATE).orElse(false));

            engine.submit(new EngineCommand.CancelRun(runId));

            until(() -> engine.snapshot(runId).map(s -> s.status() == RunStatus.CANCELLED).orElse(false));
            assertThat(stateStore.loadAudit(runId)).extracting(AuditEvent::type).contains("run.cancelled");
        }
    }

    @Test
    void unknownRuntimeRefEmitsRunPausedAndIncidentRaised(@TempDir Path repo) throws IOException {
        Path promptDir = repo.resolve("prompts");
        Files.createDirectories(promptDir);
        Files.writeString(promptDir.resolve("work.md"), "Do the thing");

        AgentStep agent = new AgentStep("work", List.of(), "not-a-configured-runtime", Path.of("prompts/work.md"),
                List.of(), List.of(), List.of(), RetryPolicy.DEFAULT, BUDGET);
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(agent));
        InMemoryStateStore stateStore = new InMemoryStateStore();

        try (PipelineEngine engine = new PipelineEngine(stateStore, throwingAgent(), FixtureScriptRunnerPort.alwaysOk())) {
            RunId runId = engine.start(TestProjects.minimal(repo), definition, "feature-x");

            until(() -> engine.snapshot(runId).map(s -> s.status() == RunStatus.PAUSED).orElse(false));

            List<AuditEvent> audit = stateStore.loadAudit(runId);
            AuditEvent paused = audit.stream().filter(e -> e.type().equals("run.paused")).findFirst().orElseThrow();
            assertThat(paused.payload().get("reason").asText()).isEqualTo("ENGINE_ERROR");
            AuditEvent incident = audit.stream().filter(e -> e.type().equals("incident.raised")).findFirst().orElseThrow();
            assertThat(incident.stepId()).isEqualTo("work");
            assertThat(incident.payload().get("reason").asText()).isEqualTo("SCRIPT");
        }
    }

    private static FixtureAgentRuntimePort throwingAgent() {
        return new FixtureAgentRuntimePort(inv -> {
            throw new AssertionError("agent runtime should not be called in this scenario");
        });
    }
}
