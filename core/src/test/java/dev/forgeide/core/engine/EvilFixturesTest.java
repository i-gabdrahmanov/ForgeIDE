package dev.forgeide.core.engine;

import dev.forgeide.core.audit.AuditEvent;
import dev.forgeide.core.engine.support.EvilAgentRuntime;
import dev.forgeide.core.engine.support.FixtureAgentRuntimePort;
import dev.forgeide.core.engine.support.FixtureScriptRunnerPort;
import dev.forgeide.core.engine.support.InMemoryStateStore;
import dev.forgeide.core.engine.support.TestProjects;
import dev.forgeide.core.event.EngineCommand;
import dev.forgeide.core.pipeline.AgentStep;
import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.policy.RetryPolicy;
import dev.forgeide.core.policy.TokenBudget;
import dev.forgeide.core.port.AgentResult;
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
import java.util.Optional;

import static dev.forgeide.core.engine.support.Await.until;
import static dev.forgeide.core.engine.support.Snapshots.statusOf;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * T19 anti-bypass acceptance (SDD §7): the "злые фикстуры" that don't need a real git repo or a
 * real OS process to demonstrate — Т-12 (pipeline.yaml/prompt edited mid-run), Т-14 (artifact
 * spoofing) and Т-15 (question-loop) — plus one FR-11 taxonomy gap-fill ({@code FAILED(stream)}
 * exhausting its retry budget, which existing coverage only shows *succeeding* after one retry,
 * never going terminal). Т-1/Т-4/Т-7/Т-9/Т-13 need real git/process plumbing and live in {@code
 * runtime}'s {@code EvilFixturesRuntimeTest}.
 */
class EvilFixturesTest {

    private static final TokenBudget BUDGET = new TokenBudget(1_000, Duration.ofMinutes(5), 10);

    @Test
    void emptyArtifactFailsTheStepWithArtifactsReason(@TempDir Path repo) throws IOException {
        Path promptDir = repo.resolve("prompts");
        Files.createDirectories(promptDir);
        Files.writeString(promptDir.resolve("work.md"), "Do the thing");
        Path artifact = repo.resolve("out/report.md");

        AgentStep agent = new AgentStep("work", List.of(), "claude", Path.of("prompts/work.md"),
                List.of(Path.of("out/report.md")), List.of(), List.of(), RetryPolicy.DEFAULT, BUDGET);
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(agent));
        InMemoryStateStore stateStore = new InMemoryStateStore();

        try (PipelineEngine engine = new PipelineEngine(stateStore, EvilAgentRuntime.emptyArtifact(artifact),
                FixtureScriptRunnerPort.alwaysOk())) {
            RunId runId = engine.start(TestProjects.minimal(repo), definition, "feature-x");

            until(() -> engine.snapshot(runId).map(s -> statusOf(s, "work") == StepStatus.FAILED).orElse(false));
            RunSnapshot snapshot = engine.snapshot(runId).orElseThrow();
            assertThat(snapshot.steps().stream().filter(s -> s.stepId().equals("work")).findFirst().orElseThrow()
                    .failureReason()).contains(FailureReason.ARTIFACTS);
            assertThat(Files.exists(artifact)).isTrue(); // the file is there — just empty
            assertThat(stateStore.audit().stream().anyMatch(e -> e.type().equals("step.failed")
                    && e.payload().get("detail").asText().contains("empty artifact"))).isTrue();
        }
    }

    @Test
    void garbageJsonArtifactFailsTheStepWithArtifactsReason(@TempDir Path repo) throws IOException {
        Path promptDir = repo.resolve("prompts");
        Files.createDirectories(promptDir);
        Files.writeString(promptDir.resolve("work.md"), "Do the thing");
        Path artifact = repo.resolve("out/report.json");

        AgentStep agent = new AgentStep("work", List.of(), "claude", Path.of("prompts/work.md"),
                List.of(Path.of("out/report.json")), List.of(), List.of(), RetryPolicy.DEFAULT, BUDGET);
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(agent));
        InMemoryStateStore stateStore = new InMemoryStateStore();

        try (PipelineEngine engine = new PipelineEngine(stateStore, EvilAgentRuntime.garbageJsonArtifact(artifact),
                FixtureScriptRunnerPort.alwaysOk())) {
            RunId runId = engine.start(TestProjects.minimal(repo), definition, "feature-x");

            until(() -> engine.snapshot(runId).map(s -> statusOf(s, "work") == StepStatus.FAILED).orElse(false));
            RunSnapshot snapshot = engine.snapshot(runId).orElseThrow();
            assertThat(snapshot.steps().stream().filter(s -> s.stepId().equals("work")).findFirst().orElseThrow()
                    .failureReason()).contains(FailureReason.ARTIFACTS);
        }
    }

    @Test
    void questionLoopEscalatesAfterTwoRoundsAndFailsWithQuestionsReason(@TempDir Path repo) throws IOException {
        Path promptDir = repo.resolve("prompts");
        Files.createDirectories(promptDir);
        Files.writeString(promptDir.resolve("work.md"), "Do the thing");

        AgentStep agent = new AgentStep("work", List.of(), "claude", Path.of("prompts/work.md"),
                List.of(), List.of(), List.of(), RetryPolicy.DEFAULT, BUDGET);
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(agent));
        InMemoryStateStore stateStore = new InMemoryStateStore();

        EvilAgentRuntime.QuestionLoop agentRuntime = EvilAgentRuntime.questionLoop();
        try (PipelineEngine engine = new PipelineEngine(stateStore, agentRuntime, FixtureScriptRunnerPort.alwaysOk())) {
            RunId runId = engine.start(TestProjects.minimal(repo), definition, "feature-x");

            // Each wait is pinned on the call count, not just the status — a plain status check
            // would race against the round already in flight still being processed (same
            // reasoning as PipelineEngineTransitionsTest's own question-loop test).
            until(() -> agentRuntime.calls() == 1
                    && engine.snapshot(runId).map(s -> statusOf(s, "work") == StepStatus.WAITING_INPUT).orElse(false));
            engine.submit(new EngineCommand.QuestionsAnswered(runId, "work", java.util.Map.of("q1", "sure")));

            until(() -> agentRuntime.calls() == 2
                    && engine.snapshot(runId).map(s -> statusOf(s, "work") == StepStatus.WAITING_INPUT).orElse(false));
            engine.submit(new EngineCommand.QuestionsAnswered(runId, "work", java.util.Map.of("q2", "still sure")));

            until(() -> agentRuntime.calls() == 3
                    && engine.snapshot(runId).map(s -> statusOf(s, "work") == StepStatus.WAITING_GATE).orElse(false));
            engine.submit(new EngineCommand.GateAnswered(runId, "work",
                    dev.forgeide.core.run.QuestionEscalationAction.CANCEL.token(), "tester", Instant.now()));

            until(() -> engine.snapshot(runId).map(s -> statusOf(s, "work") == StepStatus.FAILED).orElse(false));
            RunSnapshot snapshot = engine.snapshot(runId).orElseThrow();
            assertThat(snapshot.steps().stream().filter(s -> s.stepId().equals("work")).findFirst().orElseThrow()
                    .failureReason()).contains(FailureReason.QUESTIONS);
        }
    }

    @Test
    void editingPipelineAndPromptFilesMidPhaseHasNoEffectOnTheRunningIterationButWarnsOnRetry(
            @TempDir Path repo) throws IOException {
        Path pipelineYaml = repo.resolve(".forgeide/pipeline.yaml");
        Files.createDirectories(pipelineYaml.getParent());
        Files.writeString(pipelineYaml, "steps: [{id: work, type: agent}]\n");
        Path promptDir = repo.resolve("prompts");
        Files.createDirectories(promptDir);
        Path promptFile = promptDir.resolve("work.md");
        Files.writeString(promptFile, "Original prompt, do the thing");

        AgentStep agent = new AgentStep("work", List.of(), "claude", Path.of("prompts/work.md"),
                List.of(), List.of(), List.of(), RetryPolicy.DEFAULT, BUDGET);
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(agent));
        InMemoryStateStore stateStore = new InMemoryStateStore();

        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        FixtureAgentRuntimePort agentRuntime = new FixtureAgentRuntimePort(inv -> {
            int n = calls.incrementAndGet();
            // The evil rewrite happens during the phase itself — after the engine already
            // rendered/captured this invocation's prompt from the run-start snapshot (FR-3.5).
            EvilAgentRuntime.editsDefinitionFilesMidRun(pipelineYaml, promptFile).execute(inv, e -> { });
            if (n == 1) {
                assertThat(inv.prompt()).contains("Original prompt");
                return new AgentResult(0, Optional.empty(), new TokenUsage(1, 1), Path.of("raw.log")); // FAILED(stream)
            }
            // The auto-retry re-renders from the SAME snapshot, not the file the phase just wrote.
            assertThat(inv.prompt()).contains("Original prompt");
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var json = mapper.createObjectNode();
            json.put("step_id", "work");
            return new AgentResult(0, Optional.of(json), new TokenUsage(1, 1), Path.of("raw.log"));
        });

        try (PipelineEngine engine = new PipelineEngine(stateStore, agentRuntime, FixtureScriptRunnerPort.alwaysOk())) {
            RunId runId = engine.start(TestProjects.minimal(repo), definition, "feature-x");

            until(() -> engine.snapshot(runId).map(s -> s.status() == RunStatus.COMPLETED).orElse(false));
            assertThat(calls.get()).isEqualTo(2);
            assertThat(statusOf(engine.snapshot(runId).orElseThrow(), "work")).isEqualTo(StepStatus.PASSED);

            // Visibility, not prevention: the disk edit is real and shows up as a warning even
            // though it never touched what the run actually executed with.
            AuditEvent drift = stateStore.audit().stream()
                    .filter(e -> e.type().equals("prompt.drift")).findFirst().orElseThrow();
            assertThat(drift.payload().get("diff").asText()).contains("unconstrained");
            // Nothing ever read the tampered pipeline.yaml — the run's own graph is untouched.
            assertThat(Files.readString(pipelineYaml)).contains("sneaky rewrite");
        }
    }

    @Test
    void streamFailureExhaustsItsAutoRetryBudgetThenGoesTerminal(@TempDir Path repo) throws IOException {
        Path promptDir = repo.resolve("prompts");
        Files.createDirectories(promptDir);
        Files.writeString(promptDir.resolve("work.md"), "Do the thing");

        AgentStep agent = new AgentStep("work", List.of(), "claude", Path.of("prompts/work.md"),
                List.of(), List.of(), List.of(), RetryPolicy.DEFAULT, BUDGET);
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(agent));
        InMemoryStateStore stateStore = new InMemoryStateStore();

        // RetryPolicy.DEFAULT auto-retries a stream failure once; a second consecutive failure
        // must go terminal, not retry forever.
        FixtureAgentRuntimePort agentRuntime = new FixtureAgentRuntimePort(inv ->
                new AgentResult(0, Optional.empty(), new TokenUsage(1, 1), Path.of("raw.log")));

        try (PipelineEngine engine = new PipelineEngine(stateStore, agentRuntime, FixtureScriptRunnerPort.alwaysOk())) {
            RunId runId = engine.start(TestProjects.minimal(repo), definition, "feature-x");

            until(() -> engine.snapshot(runId).map(s -> statusOf(s, "work") == StepStatus.FAILED).orElse(false));
            RunSnapshot snapshot = engine.snapshot(runId).orElseThrow();
            assertThat(snapshot.steps().stream().filter(s -> s.stepId().equals("work")).findFirst().orElseThrow()
                    .failureReason()).contains(FailureReason.STREAM);
            assertThat(stateStore.audit().stream().filter(e -> e.type().equals("step.failed")).count()).isEqualTo(2);
            assertThat(stateStore.audit().stream().filter(e -> e.type().equals("step.retried")).count()).isEqualTo(1);
            // Blocked from a plain retry? No — STREAM isn't a security incident (unlike
            // SCOPE/TAMPERED), a human retry is still allowed; just proving it stayed FAILED
            // rather than silently retrying a third time on its own.
            assertThat(snapshot.status()).isEqualTo(RunStatus.RUNNING);
        }
    }
}
