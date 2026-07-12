package dev.forgeide.core.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.forgeide.core.audit.AuditEvent;
import dev.forgeide.core.engine.support.FixtureAgentRuntimePort;
import dev.forgeide.core.engine.support.FixtureHarnessGuardPort;
import dev.forgeide.core.engine.support.FixtureScriptRunnerPort;
import dev.forgeide.core.engine.support.InMemoryStateStore;
import dev.forgeide.core.engine.support.TestProjects;
import dev.forgeide.core.event.EngineCommand;
import dev.forgeide.core.pipeline.AgentStep;
import dev.forgeide.core.pipeline.JudgeStep;
import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.policy.FailPolicy;
import dev.forgeide.core.policy.RetryPolicy;
import dev.forgeide.core.policy.TokenBudget;
import dev.forgeide.core.port.AgentResult;
import dev.forgeide.core.port.ManifestProjectorPort;
import dev.forgeide.core.port.OutwardActionsPort;
import dev.forgeide.core.port.ScopeDiffPort;
import dev.forgeide.core.port.SecretStorePort;
import dev.forgeide.core.port.TokenUsage;
import dev.forgeide.core.run.FailureReason;
import dev.forgeide.core.run.QuestionEscalationAction;
import dev.forgeide.core.run.RunId;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.forgeide.core.engine.support.Await.until;
import static dev.forgeide.core.engine.support.Snapshots.statusOf;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * T20 acceptance: FR-8.2 (prompt edit through the trusted path applies only to the step's next
 * dispatch, audited with a diff-hash) and FR-8.3 (judge/hook script edit routes through {@link
 * dev.forgeide.core.port.HarnessGuardPort#edit}, audited as {@code harness.edited}). Also covers
 * T25/FR-10.5: the {@code pending_questions} round-limit escalation's "Повторить с новым
 * промптом" shortcut, which is nothing but this same trusted edit composed with the existing
 * {@code cancel} escalation answer and manual {@code RetryStep} — no new engine command.
 */
class PipelineEngineTrustedEditTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TokenBudget BUDGET = new TokenBudget(1_000, Duration.ofMinutes(5), 10);

    @Test
    void promptEditedAppliesOnlyToTheNextDispatchAndAudits(@TempDir Path repo) throws IOException {
        Path promptDir = repo.resolve("prompts");
        Files.createDirectories(promptDir);
        Path promptFile = promptDir.resolve("work.md");
        Files.writeString(promptFile, "Original prompt");

        AgentStep agent = new AgentStep("work", List.of(), "claude", Path.of("prompts/work.md"),
                List.of(), List.of(), List.of(), RetryPolicy.DEFAULT, BUDGET);
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(agent));
        InMemoryStateStore stateStore = new InMemoryStateStore();

        List<String> seenPrompts = new CopyOnWriteArrayList<>();
        FixtureAgentRuntimePort agentRuntime = new FixtureAgentRuntimePort(inv -> {
            seenPrompts.add(inv.prompt());
            // First two attempts (initial dispatch + the default policy's one auto-retry) fail
            // the stream so a terminal FAILED is reachable without a judge; the third (the
            // manual retry issued after the edit below) succeeds.
            if (seenPrompts.size() <= 2) {
                return new AgentResult(0, Optional.empty(), new TokenUsage(1, 1), Path.of("raw.log"));
            }
            ObjectNode json = MAPPER.createObjectNode();
            json.put("step_id", "work");
            return new AgentResult(0, Optional.of(json), new TokenUsage(1, 1), Path.of("raw.log"));
        });

        try (PipelineEngine engine = new PipelineEngine(stateStore, agentRuntime, FixtureScriptRunnerPort.alwaysOk())) {
            RunId runId = engine.start(TestProjects.minimal(repo), definition, "feature-x");

            until(() -> engine.snapshot(runId).map(s -> statusOf(s, "work") == StepStatus.FAILED).orElse(false));
            assertThat(seenPrompts).containsExactly("Original prompt", "Original prompt");

            engine.submit(new EngineCommand.PromptEdited(runId, "work", "Edited prompt, mid-run",
                    "alice", Instant.now()));

            until(() -> stateStore.audit().stream().anyMatch(e -> e.type().equals("prompt.edited")));
            AuditEvent edited = stateStore.audit().stream()
                    .filter(e -> e.type().equals("prompt.edited")).findFirst().orElseThrow();
            assertThat(edited.stepId()).isEqualTo("work");
            assertThat(edited.payload().get("user").asText()).isEqualTo("alice");
            assertThat(edited.payload().get("diff").asText()).contains("Edited prompt");
            assertThat(edited.payload().get("diffHash").asText()).hasSize(64); // sha256 hex

            assertThat(Files.readString(promptFile)).isEqualTo("Edited prompt, mid-run");
            // The two already-issued invocations were never rewritten by the edit.
            assertThat(seenPrompts).containsExactly("Original prompt", "Original prompt");

            engine.submit(new EngineCommand.RetryStep(runId, "work"));
            until(() -> engine.snapshot(runId).map(s -> s.status() == RunStatus.COMPLETED).orElse(false));

            assertThat(seenPrompts).containsExactly("Original prompt", "Original prompt", "Edited prompt, mid-run");
        }
    }

    /**
     * T25 end-to-end: a question-round escalation resolved with the "Повторить с новым промптом"
     * shortcut — {@code cancel} (ends the attempt {@code FAILED(questions)}), the T20 trusted
     * {@code PromptEdited}, then the ordinary manual {@code RetryStep} — completes the phase with
     * the edited prompt, exactly like a plain FAILED-tile retry after an edit.
     */
    @Test
    void questionEscalationEditPromptThenRetryPassesThePhase(@TempDir Path repo) throws IOException {
        Path promptDir = repo.resolve("prompts");
        Files.createDirectories(promptDir);
        Path promptFile = promptDir.resolve("work.md");
        Files.writeString(promptFile, "Original prompt");

        AgentStep agent = new AgentStep("work", List.of(), "claude", Path.of("prompts/work.md"),
                List.of(), List.of(), List.of(), RetryPolicy.DEFAULT, BUDGET);
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(agent));
        InMemoryStateStore stateStore = new InMemoryStateStore();

        AtomicInteger calls = new AtomicInteger();
        // The first 3 attempts (rounds 1-2 plus the round that trips FR-10.5's limit) keep asking
        // the same question; only the 4th — the manual retry issued after the prompt edit below —
        // finally completes the phase.
        FixtureAgentRuntimePort agentRuntime = new FixtureAgentRuntimePort(inv -> {
            int call = calls.incrementAndGet();
            ObjectNode json = MAPPER.createObjectNode();
            json.put("step_id", "work");
            if (call <= 3) {
                ArrayNode questions = json.putArray("pending_questions");
                ObjectNode q = questions.addObject();
                q.put("id", "q1");
                q.put("text", "Which epic?");
                q.put("type", "text");
            }
            return new AgentResult(0, Optional.of(json), new TokenUsage(1, 1), Path.of("raw.log"));
        });

        try (PipelineEngine engine = new PipelineEngine(stateStore, agentRuntime, FixtureScriptRunnerPort.alwaysOk())) {
            RunId runId = engine.start(TestProjects.minimal(repo), definition, "feature-x");

            until(() -> calls.get() == 1
                    && engine.snapshot(runId).map(s -> statusOf(s, "work") == StepStatus.WAITING_INPUT).orElse(false));
            engine.submit(new EngineCommand.QuestionsAnswered(runId, "work", Map.of("q1", "round-1")));

            until(() -> calls.get() == 2
                    && engine.snapshot(runId).map(s -> statusOf(s, "work") == StepStatus.WAITING_INPUT).orElse(false));
            engine.submit(new EngineCommand.QuestionsAnswered(runId, "work", Map.of("q1", "round-2")));

            until(() -> calls.get() == 3
                    && engine.snapshot(runId).map(s -> statusOf(s, "work") == StepStatus.WAITING_GATE).orElse(false));

            // "Повторить с новым промптом": cancel this escalated attempt...
            engine.submit(new EngineCommand.GateAnswered(runId, "work",
                    QuestionEscalationAction.CANCEL.token(), "tester", Instant.now()));
            until(() -> engine.snapshot(runId).map(s -> statusOf(s, "work") == StepStatus.FAILED).orElse(false));
            assertThat(engine.snapshot(runId).orElseThrow().steps().stream()
                    .filter(s -> s.stepId().equals("work")).findFirst().orElseThrow().failureReason())
                    .contains(FailureReason.QUESTIONS);

            // ...edit the prompt through the T20 trusted path...
            engine.submit(new EngineCommand.PromptEdited(runId, "work", "Edited prompt, no more questions",
                    "alice", Instant.now()));
            until(() -> stateStore.audit().stream().anyMatch(e -> e.type().equals("prompt.edited")));
            assertThat(Files.readString(promptFile)).isEqualTo("Edited prompt, no more questions");

            // ...then retry, same command a FAILED tile's own button sends.
            engine.submit(new EngineCommand.RetryStep(runId, "work"));
            until(() -> engine.snapshot(runId).map(s -> s.status() == RunStatus.COMPLETED).orElse(false));

            assertThat(calls.get()).isEqualTo(4);
        }
    }

    @Test
    void promptEditedResolvesAJudgeStepsLlmPromptByTheJudgesOwnId(@TempDir Path repo) throws IOException {
        Path promptDir = repo.resolve("prompts");
        Files.createDirectories(promptDir);
        Files.writeString(promptDir.resolve("work.md"), "work prompt");
        Files.writeString(promptDir.resolve("judge-llm.md"), "Original judge prompt");

        AgentStep work = new AgentStep("work", List.of(), "claude", Path.of("prompts/work.md"),
                List.of(), List.of(), List.of(), RetryPolicy.DEFAULT, BUDGET);
        AgentStep judgeLlm = new AgentStep("judge-red-llm", List.of(), "claude", Path.of("prompts/judge-llm.md"),
                List.of(), List.of(), List.of(), RetryPolicy.DEFAULT, BUDGET);
        JudgeStep judge = new JudgeStep("judge-red", List.of("work"), "work",
                Optional.of(judgeLlm), Optional.empty(), FailPolicy.DEFAULT);
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(work, judge));
        InMemoryStateStore stateStore = new InMemoryStateStore();

        // "work" never reaches PASSED (empty finalJson -> FAILED(stream), no judge verdict is
        // ever ingested) so "judge-red" stays PENDING and is never dispatched — this test only
        // cares that the edit resolves/writes the judge's *llm* prompt file while the run exists,
        // not about the judge actually running.
        FixtureAgentRuntimePort agentRuntime = new FixtureAgentRuntimePort(inv ->
                new AgentResult(0, Optional.empty(), new TokenUsage(1, 1), Path.of("raw.log")));

        try (PipelineEngine engine = new PipelineEngine(stateStore, agentRuntime, FixtureScriptRunnerPort.alwaysOk())) {
            RunId runId = engine.start(TestProjects.minimal(repo), definition, "feature-x");
            until(() -> engine.snapshot(runId).map(s -> statusOf(s, "work") == StepStatus.FAILED).orElse(false));

            engine.submit(new EngineCommand.PromptEdited(runId, "judge-red", "Edited judge prompt",
                    "bob", Instant.now()));

            until(() -> stateStore.audit().stream().anyMatch(e -> e.type().equals("prompt.edited")));
            AuditEvent edited = stateStore.audit().stream()
                    .filter(e -> e.type().equals("prompt.edited")).findFirst().orElseThrow();
            assertThat(edited.stepId()).isEqualTo("judge-red");
            assertThat(edited.payload().get("promptPath").asText()).isEqualTo("prompts/judge-llm.md");
            assertThat(Files.readString(promptDir.resolve("judge-llm.md"))).isEqualTo("Edited judge prompt");
        }
    }

    @Test
    void harnessEditedRoutesThroughTheGuardAndAudits(@TempDir Path repo) throws IOException {
        Path promptDir = repo.resolve("prompts");
        Files.createDirectories(promptDir);
        Files.writeString(promptDir.resolve("work.md"), "work");
        AgentStep work = new AgentStep("work", List.of(), "claude", Path.of("prompts/work.md"),
                List.of(), List.of(), List.of(), RetryPolicy.DEFAULT, BUDGET);
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(work));
        InMemoryStateStore stateStore = new InMemoryStateStore();
        FixtureHarnessGuardPort harnessGuard = new FixtureHarnessGuardPort();

        FixtureAgentRuntimePort agentRuntime = new FixtureAgentRuntimePort(inv -> {
            ObjectNode json = MAPPER.createObjectNode();
            json.put("step_id", "work");
            return new AgentResult(0, Optional.of(json), new TokenUsage(1, 1), Path.of("raw.log"));
        });

        try (PipelineEngine engine = new PipelineEngine(stateStore, agentRuntime, FixtureScriptRunnerPort.alwaysOk(),
                ManifestProjectorPort.NOOP, ScopeDiffPort.NOOP, SecretStorePort.NOOP, OutwardActionsPort.NOOP,
                harnessGuard)) {
            RunId runId = engine.start(TestProjects.minimal(repo), definition, "feature-x");
            until(() -> engine.snapshot(runId).map(s -> s.status() == RunStatus.COMPLETED).orElse(false));

            engine.submit(new EngineCommand.HarnessEdited(runId, "hooks/tdd-guard.py",
                    "print('edited via ide')\n", "carol", Instant.now()));

            until(() -> stateStore.audit().stream().anyMatch(e -> e.type().equals("harness.edited")));
            assertThat(harnessGuard.editCalls).hasSize(1);
            assertThat(harnessGuard.editCalls.get(0).relativePath()).isEqualTo("hooks/tdd-guard.py");
            assertThat(harnessGuard.editCalls.get(0).content()).isEqualTo("print('edited via ide')\n");

            AuditEvent edited = stateStore.audit().stream()
                    .filter(e -> e.type().equals("harness.edited")).findFirst().orElseThrow();
            assertThat(edited.payload().get("relativePath").asText()).isEqualTo("hooks/tdd-guard.py");
            assertThat(edited.payload().get("oldHash").asText()).isEqualTo("old-hash");
            assertThat(edited.payload().get("newHash").asText()).isEqualTo("new-hash");
            assertThat(edited.payload().get("user").asText()).isEqualTo("carol");
        }
    }
}
