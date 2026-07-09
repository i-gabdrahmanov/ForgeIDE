package dev.forgeide.core.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.forgeide.core.audit.AuditEvent;
import dev.forgeide.core.engine.support.FixtureAgentRuntimePort;
import dev.forgeide.core.event.EngineCommand;
import dev.forgeide.core.engine.support.FixtureOutwardActionsPort;
import dev.forgeide.core.engine.support.FixtureScriptRunnerPort;
import dev.forgeide.core.engine.support.InMemoryStateStore;
import dev.forgeide.core.pipeline.AgentStep;
import dev.forgeide.core.pipeline.BranchStep;
import dev.forgeide.core.pipeline.GateStep;
import dev.forgeide.core.pipeline.OutwardAction;
import dev.forgeide.core.pipeline.OutwardStep;
import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.ScriptStep;
import dev.forgeide.core.policy.RetryPolicy;
import dev.forgeide.core.policy.TokenBudget;
import dev.forgeide.core.port.AgentResult;
import dev.forgeide.core.port.ManifestProjectorPort;
import dev.forgeide.core.port.OutwardActionException;
import dev.forgeide.core.port.OutwardActionsPort;
import dev.forgeide.core.port.ScopeDiffPort;
import dev.forgeide.core.port.SecretStorePort;
import dev.forgeide.core.port.TokenUsage;
import dev.forgeide.core.project.CriticalityProfile;
import dev.forgeide.core.project.OutwardConfig;
import dev.forgeide.core.project.PrProvider;
import dev.forgeide.core.project.PrRepoConfig;
import dev.forgeide.core.project.ProjectDefinition;
import dev.forgeide.core.project.ProjectId;
import dev.forgeide.core.project.RuntimeBinding;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static dev.forgeide.core.engine.support.Await.until;
import static dev.forgeide.core.engine.support.Snapshots.statusOf;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * T17 acceptance (SR-4): outward steps deliver through {@link OutwardActionsPort}, never inline,
 * and only after their gate — the four bullets under "Приёмка" in {@code
 * T17-outward-actions.md}, minus the real-git/real-HTTP end of them (that's {@code
 * DefaultOutwardActionsPortTest} in {@code runtime}).
 */
class PipelineEngineOutwardTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TokenBudget BUDGET = new TokenBudget(1_000, Duration.ofMinutes(5), 10);

    @Test
    void confirmedGateDeliversAndRecordsResultRefs(@TempDir Path repo) {
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(
                gate(), branch(), deliver(RetryPolicy.DEFAULT), rejectedSink()));

        FixtureOutwardActionsPort outward = new FixtureOutwardActionsPort();
        InMemoryStateStore stateStore = new InMemoryStateStore();
        try (PipelineEngine engine = engine(stateStore, outward, FixtureScriptRunnerPort.alwaysOk(), SecretStorePort.NOOP)) {
            RunId runId = engine.start(projectWithOutward(repo), definition, "feature-x");
            until(() -> engine.snapshot(runId).map(s -> statusOf(s, "gate") == StepStatus.WAITING_GATE).orElse(false));
            engine.submit(new EngineCommand.GateAnswered(runId, "gate", "confirm", "alice", Instant.now()));

            until(() -> engine.snapshot(runId).map(s -> s.status() == RunStatus.COMPLETED).orElse(false));

            assertThat(statusOf(engine.snapshot(runId).orElseThrow(), "deliver")).isEqualTo(StepStatus.PASSED);
            assertThat(statusOf(engine.snapshot(runId).orElseThrow(), "rejected")).isEqualTo(StepStatus.SKIPPED);
        }

        assertThat(outward.calls()).hasSize(2);
        var pushed = (OutwardActionsPort.GitPushRequest) outward.calls().get(0);
        assertThat(pushed.branch()).isEqualTo("feature-x/deliver");
        var pr = (OutwardActionsPort.CreatePrRequest) outward.calls().get(1);
        assertThat(pr.sourceBranch()).isEqualTo("feature-x/deliver");
        assertThat(pr.targetBranch()).isEqualTo("main");

        AuditEvent result = stateStore.audit().stream().filter(e -> e.type().equals("outward.result")).findFirst().orElseThrow();
        assertThat(result.payload().get("resultRefs").get("pr_url").asText()).isEqualTo("https://example/pr/1");
    }

    @Test
    void rejectedGateNeverDispatchesTheOutwardStep(@TempDir Path repo) {
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(
                gate(), branch(), deliver(RetryPolicy.DEFAULT), rejectedSink()));

        FixtureOutwardActionsPort outward = new FixtureOutwardActionsPort();
        try (PipelineEngine engine = engine(new InMemoryStateStore(), outward, FixtureScriptRunnerPort.alwaysOk(), SecretStorePort.NOOP)) {
            RunId runId = engine.start(projectWithOutward(repo), definition, "feature-x");
            until(() -> engine.snapshot(runId).map(s -> statusOf(s, "gate") == StepStatus.WAITING_GATE).orElse(false));
            engine.submit(new EngineCommand.GateAnswered(runId, "gate", "reject", "alice", Instant.now()));

            until(() -> engine.snapshot(runId).map(s -> s.status() == RunStatus.COMPLETED).orElse(false));

            assertThat(statusOf(engine.snapshot(runId).orElseThrow(), "deliver")).isEqualTo(StepStatus.SKIPPED);
        }

        assertThat(outward.calls()).isEmpty();
    }

    @Test
    void retryAfterANetworkFailureReusesTheSameBranchAndDoesNotDoubleUpTheResult(@TempDir Path repo) {
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(
                gate(), branch(), deliver(new RetryPolicy(0, 1)), rejectedSink()));

        AtomicInteger pushAttempts = new AtomicInteger();
        FixtureOutwardActionsPort outward = new FixtureOutwardActionsPort()
                .onGitPush((request, attempt) -> {
                    pushAttempts.incrementAndGet();
                    if (attempt == 1) {
                        throw new OutwardActionException("simulated network failure");
                    }
                    return OutwardActionsPort.Outcome.EMPTY;
                });

        try (PipelineEngine engine = engine(new InMemoryStateStore(), outward, FixtureScriptRunnerPort.alwaysOk(), SecretStorePort.NOOP)) {
            RunId runId = engine.start(projectWithOutward(repo), definition, "feature-x");
            until(() -> engine.snapshot(runId).map(s -> statusOf(s, "gate") == StepStatus.WAITING_GATE).orElse(false));
            engine.submit(new EngineCommand.GateAnswered(runId, "gate", "confirm", "alice", Instant.now()));

            until(() -> engine.snapshot(runId).map(s -> s.status() == RunStatus.COMPLETED).orElse(false), 5_000);
        }

        assertThat(pushAttempts.get()).isEqualTo(2);
        List<OutwardActionsPort.GitPushRequest> pushes = outward.calls().stream()
                .filter(c -> c instanceof OutwardActionsPort.GitPushRequest)
                .map(c -> (OutwardActionsPort.GitPushRequest) c)
                .toList();
        assertThat(pushes).hasSize(2);
        assertThat(pushes.get(0).branch()).isEqualTo(pushes.get(1).branch());
    }

    @Test
    void outwardEnvScopeNeverLeaksIntoASiblingAgentStepAndViceVersa(@TempDir Path repo) throws IOException {
        Path promptDir = repo.resolve("prompts");
        Files.createDirectories(promptDir);
        Files.writeString(promptDir.resolve("ground.md"), "ground");

        AgentStep ground = new AgentStep("ground", List.of(), "claude", Path.of("prompts/ground.md"),
                List.of(), List.of(), List.of(), RetryPolicy.DEFAULT, BUDGET);
        OutwardStep push = new OutwardStep("push", List.of(), List.of(OutwardAction.GIT_PUSH), List.of("GIT_TOKEN"));
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(ground, push));

        AtomicReference<Map<String, String>> envSeenByAgent = new AtomicReference<>();
        FixtureAgentRuntimePort agentRuntime = new FixtureAgentRuntimePort(invocation -> {
            envSeenByAgent.set(invocation.env());
            ObjectNode json = MAPPER.createObjectNode();
            json.put("step_id", "ground");
            return new AgentResult(0, Optional.of(json), new TokenUsage(1, 1), Path.of("raw.log"));
        });
        FixtureOutwardActionsPort outward = new FixtureOutwardActionsPort();
        SecretStorePort secretStore = envScope -> envScope.contains("GIT_TOKEN") ? Map.of("GIT_TOKEN", "s3cr3t") : Map.of();

        try (PipelineEngine engine = new PipelineEngine(new InMemoryStateStore(), agentRuntime,
                FixtureScriptRunnerPort.alwaysOk(), ManifestProjectorPort.NOOP, ScopeDiffPort.NOOP, secretStore, outward)) {
            RunId runId = engine.start(projectWithOutward(repo), definition, "feature-x");
            until(() -> engine.snapshot(runId).map(s -> s.status() == RunStatus.COMPLETED).orElse(false));
        }

        var pushed = (OutwardActionsPort.GitPushRequest) outward.calls().get(0);
        assertThat(pushed.env()).containsExactly(Map.entry("GIT_TOKEN", "s3cr3t"));
        assertThat(envSeenByAgent.get()).isEmpty();
    }

    // ---- fixtures -----------------------------------------------------------------------

    private static PipelineEngine engine(InMemoryStateStore stateStore, OutwardActionsPort outward,
                                          FixtureScriptRunnerPort scriptRunner, SecretStorePort secretStore) {
        return new PipelineEngine(stateStore, (inv, onEvent) -> {
            throw new AssertionError("no agent steps in this pipeline");
        }, scriptRunner, ManifestProjectorPort.NOOP, ScopeDiffPort.NOOP, secretStore, outward);
    }

    private static GateStep gate() {
        return new GateStep("gate", List.of(), "Доставить?", List.of("confirm", "reject"), List.of());
    }

    private static BranchStep branch() {
        return new BranchStep("branch", List.of("gate"), Map.of("confirm", "deliver", "reject", "rejected"));
    }

    private static OutwardStep deliver(RetryPolicy retry) {
        return new OutwardStep("deliver", List.of("branch"),
                List.of(OutwardAction.GIT_PUSH, OutwardAction.CREATE_PR), List.of(), retry);
    }

    private static ScriptStep rejectedSink() {
        return new ScriptStep("rejected", List.of("branch"), List.of("true"), Duration.ofSeconds(5));
    }

    private static ProjectDefinition projectWithOutward(Path repo) {
        OutwardConfig outwardConfig = new OutwardConfig("origin", "main",
                Optional.of(new PrRepoConfig(PrProvider.GITHUB, "https://api.github.com", "acme/demo")),
                Optional.empty());
        return new ProjectDefinition(ProjectId.newId(), "outward-test", repo, List.of(), Map.of(),
                CriticalityProfile.LOW,
                List.of(new RuntimeBinding("claude", Path.of("/usr/bin/claude"))), outwardConfig);
    }
}
