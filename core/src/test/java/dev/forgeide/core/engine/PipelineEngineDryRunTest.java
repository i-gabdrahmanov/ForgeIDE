package dev.forgeide.core.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.forgeide.core.audit.AuditEvent;
import dev.forgeide.core.engine.support.FixtureAgentRuntimePort;
import dev.forgeide.core.engine.support.FixtureHarnessGuardPort;
import dev.forgeide.core.engine.support.FixtureScriptRunnerPort;
import dev.forgeide.core.engine.support.InMemoryStateStore;
import dev.forgeide.core.engine.support.TestProjects;
import dev.forgeide.core.event.EngineCommand;
import dev.forgeide.core.event.EngineEvent;
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
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.forgeide.core.engine.support.Await.until;
import static dev.forgeide.core.engine.support.Snapshots.statusOf;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * T21 acceptance: FR-8.4 (judge dry-run never touches {@code run.json}/step status, audits only
 * {@code judge.dryrun}, reflects a T20 trusted-path script edit immediately without restarting
 * the run) and FR-8.5 (prompt preview renders byte-identical to what the next real dispatch
 * actually sends).
 */
class PipelineEngineDryRunTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TokenBudget BUDGET = new TokenBudget(1_000, Duration.ofMinutes(5), 10);

    /** {@code gate} is never answered — "work"/"review" stay {@code PENDING} for the whole test,
     * i.e. dry-running "review" happens without any progon (dispatch) of either step ever having
     * occurred (task scope: "Dry-run доступен и при отсутствии прогона"). */
    private static PipelineDefinition idlePipeline(ScriptStep check) {
        GateStep gate = new GateStep("gate", List.of(), "Proceed?", List.of("go"), List.of());
        AgentStep work = new AgentStep("work", List.of("gate"), "claude", Path.of("prompts/work.md"),
                List.of(Path.of("out.md")), List.of(), List.of(), RetryPolicy.DEFAULT, BUDGET);
        JudgeStep review = new JudgeStep("review", List.of("work"), "work",
                Optional.empty(), Optional.of(check), FailPolicy.DEFAULT);
        return new PipelineDefinition("p", 1, List.of(gate, work, review));
    }

    private static FixtureAgentRuntimePort neverCalledAgent() {
        return new FixtureAgentRuntimePort(inv -> {
            throw new AssertionError("work must never actually dispatch in this test");
        });
    }

    @Test
    void dryRunReportsMissingArtifactsWithoutRunningTheCheckOrTouchingTheRun(@TempDir Path repo) throws IOException {
        Files.createDirectories(repo.resolve("prompts"));
        Files.writeString(repo.resolve("prompts/work.md"), "Do the thing.");
        ScriptStep check = new ScriptStep("review.check", List.of(), List.of("check-target"), Duration.ofSeconds(5));
        PipelineDefinition definition = idlePipeline(check);

        AtomicInteger scriptCalls = new AtomicInteger();
        FixtureScriptRunnerPort scriptRunner = new FixtureScriptRunnerPort(inv -> {
            scriptCalls.incrementAndGet();
            return new ScriptResult(0, "ok", "");
        });
        InMemoryStateStore stateStore = new InMemoryStateStore();

        try (PipelineEngine engine = new PipelineEngine(stateStore, neverCalledAgent(), scriptRunner)) {
            RunId runId = engine.start(TestProjects.minimal(repo), definition, "feature-x");
            until(() -> engine.snapshot(runId).map(s -> statusOf(s, "gate") == StepStatus.WAITING_GATE).orElse(false));
            RunSnapshot before = engine.snapshot(runId).orElseThrow();

            List<EngineEvent.JudgeDryRunResult> results = new CopyOnWriteArrayList<>();
            engine.subscribe(e -> {
                if (e instanceof EngineEvent.JudgeDryRunResult r) {
                    results.add(r);
                }
            });
            engine.submit(new EngineCommand.JudgeDryRunRequested(runId, "review", "req-1"));

            until(() -> !results.isEmpty());
            assertThat(results.get(0).passed()).isFalse();
            assertThat(results.get(0).detail()).contains("missing artifact");
            assertThat(results.get(0).requestId()).isEqualTo("req-1");
            assertThat(scriptCalls.get()).isZero();

            until(() -> stateStore.audit().stream().anyMatch(a -> a.type().equals("judge.dryrun")));
            assertThat(stateStore.audit()).extracting(AuditEvent::type).doesNotContain("judge.verdict");
            assertThat(engine.snapshot(runId).orElseThrow()).isEqualTo(before);
            assertThat(statusOf(engine.snapshot(runId).orElseThrow(), "work")).isEqualTo(StepStatus.PENDING);
            assertThat(statusOf(engine.snapshot(runId).orElseThrow(), "review")).isEqualTo(StepStatus.PENDING);
        }
    }

    @Test
    void dryRunRunsTheCheckAgainstArtifactsOnDiskAndReflectsATrustedPathEditWithoutRestarting(
            @TempDir Path repo) throws IOException {
        Files.createDirectories(repo.resolve("prompts"));
        Files.writeString(repo.resolve("prompts/work.md"), "Do the thing.");
        Files.writeString(repo.resolve("out.md"), "artifact content");
        ScriptStep check = new ScriptStep("review.check", List.of(),
                List.of("python3", ".gigacode/hooks/check_it.py"), Duration.ofSeconds(5));
        PipelineDefinition definition = idlePipeline(check);

        FixtureHarnessGuardPort harnessGuard = new FixtureHarnessGuardPort();
        // First dry-run resolves to a script that still fails; simulating a T20 trusted-path edit
        // flips this so the *second* dry-run — same run, no restart — resolves to a fixed script.
        AtomicInteger version = new AtomicInteger(1);
        harnessGuard.cacheResolver = command -> command.stream()
                .map(t -> t.equals(".gigacode/hooks/check_it.py") ? "/cache/v" + version.get() + "/check_it.py" : t)
                .toList();

        FixtureScriptRunnerPort scriptRunner = new FixtureScriptRunnerPort(inv -> {
            String script = inv.command().get(1);
            return script.endsWith("/v1/check_it.py")
                    ? new ScriptResult(1, "", "coverage 40%")
                    : new ScriptResult(0, "coverage 95%", "");
        });
        InMemoryStateStore stateStore = new InMemoryStateStore();

        try (PipelineEngine engine = new PipelineEngine(stateStore, neverCalledAgent(), scriptRunner,
                dev.forgeide.core.port.ManifestProjectorPort.NOOP, dev.forgeide.core.port.ScopeDiffPort.NOOP,
                dev.forgeide.core.port.SecretStorePort.NOOP, dev.forgeide.core.port.OutwardActionsPort.NOOP,
                harnessGuard)) {
            RunId runId = engine.start(TestProjects.minimal(repo), definition, "feature-x");
            until(() -> engine.snapshot(runId).map(s -> statusOf(s, "gate") == StepStatus.WAITING_GATE).orElse(false));

            List<EngineEvent.JudgeDryRunResult> results = new CopyOnWriteArrayList<>();
            engine.subscribe(e -> {
                if (e instanceof EngineEvent.JudgeDryRunResult r) {
                    results.add(r);
                }
            });

            engine.submit(new EngineCommand.JudgeDryRunRequested(runId, "review", "req-1"));
            until(() -> results.size() >= 1);
            assertThat(results.get(0).passed()).isFalse();
            assertThat(results.get(0).detail()).contains("coverage 40%");

            // The "edit check-script -> dry-run" cycle (T20 save routes through
            // HarnessGuardPort#edit, which is exactly what would flip resolveFromCache's output)
            // — simulated directly on the fixture since T20's own trusted-edit plumbing is out of
            // this test's scope.
            version.set(2);

            engine.submit(new EngineCommand.JudgeDryRunRequested(runId, "review", "req-2"));
            until(() -> results.size() >= 2);
            assertThat(results.get(1).passed()).isTrue();
            assertThat(results.get(1).detail()).contains("coverage 95%");

            // Never touched run.json/status, and no restart (no run.started twice, no dispatch)
            // was needed for the second dry-run to reflect the "fixed" script.
            assertThat(statusOf(engine.snapshot(runId).orElseThrow(), "work")).isEqualTo(StepStatus.PENDING);
            assertThat(statusOf(engine.snapshot(runId).orElseThrow(), "review")).isEqualTo(StepStatus.PENDING);
            List<AuditEvent> dryRunAudits = stateStore.audit().stream()
                    .filter(a -> a.type().equals("judge.dryrun")).toList();
            assertThat(dryRunAudits).hasSize(2);
            assertThat(stateStore.audit()).extracting(AuditEvent::type).doesNotContain("judge.verdict", "step.completed");
        }
    }

    @Test
    void promptPreviewIsByteIdenticalToWhatTheNextRealDispatchActuallySends(@TempDir Path repo) throws IOException {
        Files.createDirectories(repo.resolve("prompts"));
        Files.writeString(repo.resolve("prompts/work.md"), "Do the thing, ${feature.slug}.");

        AgentStep work = new AgentStep("work", List.of(), "claude", Path.of("prompts/work.md"),
                List.of(), List.of(), List.of(), RetryPolicy.DEFAULT, BUDGET);
        ScriptStep check = new ScriptStep("review.check", List.of(), List.of("check-target"), Duration.ofSeconds(5));
        // maxIterations=1: the *first* judge FAIL already exhausts the policy and escalates
        // (WAITING_GATE) instead of silently auto-retrying "work" out from under the test — a
        // stable window in which accumulated_errors is populated but nothing is being dispatched.
        JudgeStep review = new JudgeStep("review", List.of("work"), "work",
                Optional.empty(), Optional.of(check), new FailPolicy(1));
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(work, review));

        List<String> seenPrompts = new CopyOnWriteArrayList<>();
        FixtureAgentRuntimePort agentRuntime = new FixtureAgentRuntimePort(inv -> {
            seenPrompts.add(inv.prompt());
            ObjectNode json = MAPPER.createObjectNode();
            json.put("step_id", "work");
            return new AgentResult(0, Optional.of(json), new TokenUsage(1, 1), Path.of("raw.log"));
        });
        FixtureScriptRunnerPort scriptRunner = new FixtureScriptRunnerPort(inv -> new ScriptResult(1, "", "coverage 12%"));
        InMemoryStateStore stateStore = new InMemoryStateStore();

        try (PipelineEngine engine = new PipelineEngine(stateStore, agentRuntime, scriptRunner)) {
            RunId runId = engine.start(TestProjects.minimal(repo), definition, "feature-x");

            until(() -> engine.snapshot(runId).map(s -> statusOf(s, "review") == StepStatus.WAITING_GATE).orElse(false));
            assertThat(seenPrompts).hasSize(1);

            List<EngineEvent.PromptPreviewReady> previews = new CopyOnWriteArrayList<>();
            engine.subscribe(e -> {
                if (e instanceof EngineEvent.PromptPreviewReady p) {
                    previews.add(p);
                }
            });
            engine.submit(new EngineCommand.PromptPreviewRequested(runId, "work", "preview-1"));
            until(() -> !previews.isEmpty());
            String previewedPrompt = previews.get(0).renderedPrompt();
            assertThat(previewedPrompt).contains("## accumulated_errors").contains("coverage 12%");

            // Resolve the escalation with "retry" (EscalationAction.RETRY) — the same command a
            // human clicking "повторить" in the FR-11.3 dialog submits — which redispatches
            // "work" for real.
            engine.submit(new EngineCommand.GateAnswered(runId, "review", "retry", "tester", Instant.now()));
            until(() -> seenPrompts.size() >= 2);

            assertThat(seenPrompts.get(1)).isEqualTo(previewedPrompt);
        }
    }

    @Test
    void promptPreviewOfALlmJudgeMatchesDispatchAndSkipsContextBlocks(@TempDir Path repo) throws IOException {
        Files.createDirectories(repo.resolve("prompts"));
        Files.writeString(repo.resolve("prompts/work.md"), "work prompt");
        Files.writeString(repo.resolve("prompts/judge-llm.md"), "Judge this, ${feature.slug}.");

        AgentStep work = new AgentStep("work", List.of(), "claude", Path.of("prompts/work.md"),
                List.of(), List.of(), List.of(), RetryPolicy.DEFAULT, BUDGET);
        // The llm-judge's own step id must be "<judgeId>.llm" — the same convention
        // dispatchJudge's key derivation (judge.id() + ".llm") relies on elsewhere in the engine.
        AgentStep judgeLlm = new AgentStep("judge-red.llm", List.of(), "claude", Path.of("prompts/judge-llm.md"),
                List.of(), List.of(), List.of(), RetryPolicy.DEFAULT, BUDGET);
        JudgeStep judge = new JudgeStep("judge-red", List.of("work"), "work",
                Optional.of(judgeLlm), Optional.empty(), FailPolicy.DEFAULT);
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(work, judge));

        List<String> seenLlmPrompts = new CopyOnWriteArrayList<>();
        FixtureAgentRuntimePort agentRuntime = new FixtureAgentRuntimePort(inv -> {
            if (inv.prompt().startsWith("Judge this")) {
                seenLlmPrompts.add(inv.prompt());
                ObjectNode json = MAPPER.createObjectNode();
                json.put("verdict", "pass");
                return new AgentResult(0, Optional.of(json), new TokenUsage(1, 1), Path.of("raw.log"));
            }
            ObjectNode json = MAPPER.createObjectNode();
            json.put("step_id", "work");
            return new AgentResult(0, Optional.of(json), new TokenUsage(1, 1), Path.of("raw.log"));
        });
        InMemoryStateStore stateStore = new InMemoryStateStore();

        try (PipelineEngine engine = new PipelineEngine(stateStore, agentRuntime, FixtureScriptRunnerPort.alwaysOk())) {
            RunId runId = engine.start(TestProjects.minimal(repo), definition, "feature-x");
            until(() -> engine.snapshot(runId).map(s -> s.status() == RunStatus.COMPLETED).orElse(false));

            assertThat(seenLlmPrompts).containsExactly("Judge this, feature-x.");

            List<EngineEvent.PromptPreviewReady> previews = new CopyOnWriteArrayList<>();
            engine.subscribe(e -> {
                if (e instanceof EngineEvent.PromptPreviewReady p) {
                    previews.add(p);
                }
            });
            engine.submit(new EngineCommand.PromptPreviewRequested(runId, "judge-red", "preview-llm"));
            until(() -> !previews.isEmpty());
            assertThat(previews.get(0).renderedPrompt()).isEqualTo("Judge this, feature-x.");
        }
    }
}
