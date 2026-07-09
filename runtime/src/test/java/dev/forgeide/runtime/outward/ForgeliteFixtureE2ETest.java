package dev.forgeide.runtime.outward;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import dev.forgeide.core.engine.PipelineEngine;
import dev.forgeide.core.event.EngineCommand;
import dev.forgeide.core.pipeline.AgentStep;
import dev.forgeide.core.pipeline.BranchStep;
import dev.forgeide.core.pipeline.GateStep;
import dev.forgeide.core.pipeline.JudgeStep;
import dev.forgeide.core.pipeline.OutwardAction;
import dev.forgeide.core.pipeline.OutwardStep;
import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.ScriptStep;
import dev.forgeide.core.policy.FailPolicy;
import dev.forgeide.core.policy.RetryPolicy;
import dev.forgeide.core.policy.TokenBudget;
import dev.forgeide.core.port.AgentResult;
import dev.forgeide.core.port.ManifestProjectorPort;
import dev.forgeide.core.port.ScopeDiffPort;
import dev.forgeide.core.port.ScriptResult;
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
import dev.forgeide.core.run.RunSnapshot;
import dev.forgeide.core.run.RunStatus;
import dev.forgeide.core.run.StepStatus;
import dev.forgeide.runtime.state.FileStateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * T19/SDD §7 criterion 3: "Позитивный e2e: полный forgelite на фикстурном рантайме — все гейты,
 * судьи, outward". The counterpart to {@code EvilFixturesRuntimeTest} and {@code
 * EvilFixturesTest} — proves the same real engine + real git + real judge-recheck stack completes
 * a well-behaved run end to end: an agent phase produces a real artifact, a deterministic judge
 * rechecks it, a human gate confirms delivery, and the engine's own {@code outward} step (not the
 * agent) pushes a branch and opens a PR against a fake GitHub server.
 */
class ForgeliteFixtureE2ETest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TokenBudget BUDGET = new TokenBudget(1_000, Duration.ofMinutes(5), 10);

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void agentJudgeGateAndOutwardAllCompleteThroughTheRealEngine(
            @TempDir Path base, @TempDir Path stateDir) throws IOException, InterruptedException {
        assumeGitAvailable();

        Path remote = base.resolve("remote.git");
        Path repo = base.resolve("repo");
        initBareRemote(remote);
        initWorkingRepoWithRemote(repo, remote);

        Path promptDir = repo.resolve("prompts");
        Files.createDirectories(promptDir);
        Files.writeString(promptDir.resolve("work.md"), "Deliver the feature.");
        Path artifact = repo.resolve("out/report.md");

        AtomicInteger prListCalls = new AtomicInteger();
        AtomicInteger prCreateCalls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/repos/acme/demo/pulls", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                prListCalls.incrementAndGet();
                respond(exchange, 200, "[]");
            } else {
                prCreateCalls.incrementAndGet();
                respond(exchange, 201, "{\"html_url\":\"https://github.com/acme/demo/pull/1\",\"number\":1}");
            }
        });
        server.start();

        AgentStep work = new AgentStep("work", List.of(), "claude", Path.of("prompts/work.md"),
                List.of(Path.of("out/report.md")), List.of("out/**"), List.of(), RetryPolicy.DEFAULT, BUDGET);
        ScriptStep reviewCheck = new ScriptStep("review.check", List.of(), List.of("check-report"),
                Duration.ofSeconds(5));
        JudgeStep review = new JudgeStep("review", List.of("work"), "work",
                Optional.empty(), Optional.of(reviewCheck), FailPolicy.DEFAULT);
        GateStep gate = new GateStep("gate-deliver", List.of("review"), "Доставить?", List.of("confirm", "reject"), List.of());
        BranchStep branch = new BranchStep("branch", List.of("gate-deliver"),
                Map.of("confirm", "deliver", "reject", "rejected"));
        OutwardStep deliver = new OutwardStep("deliver", List.of("branch"),
                List.of(OutwardAction.GIT_PUSH, OutwardAction.CREATE_PR), List.of());
        ScriptStep rejected = new ScriptStep("rejected", List.of("branch"), List.of("true"), Duration.ofSeconds(5));
        PipelineDefinition definition = new PipelineDefinition("forgelite", 1,
                List.of(work, review, gate, branch, deliver, rejected));

        OutwardConfig outwardConfig = new OutwardConfig("origin", "main",
                Optional.of(new PrRepoConfig(PrProvider.GITHUB, "http://localhost:" + server.getAddress().getPort(), "acme/demo")),
                Optional.empty());
        ProjectDefinition project = new ProjectDefinition(ProjectId.newId(), "forgelite-e2e", repo, List.of(), Map.of(),
                CriticalityProfile.LOW, List.of(new RuntimeBinding("claude", Path.of("/usr/bin/claude"))), outwardConfig);

        FileStateStore stateStore = new FileStateStore(stateDir);
        DefaultOutwardActionsPort outwardActions = new DefaultOutwardActionsPort(HttpClient.newHttpClient());

        // A well-behaved agent phase: writes the real artifact judge/expects both look at.
        dev.forgeide.core.port.AgentRuntimePort agentRuntime = (invocation, onEvent) -> {
            try {
                Files.createDirectories(artifact.getParent());
                Files.writeString(artifact, "TESTS_GREEN\nfeature delivered\n");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            ObjectNode json = MAPPER.createObjectNode();
            json.put("step_id", "work");
            return new AgentResult(0, Optional.of(json), new TokenUsage(1, 1), Path.of("raw.log"));
        };

        dev.forgeide.core.port.ScriptRunnerPort scriptRunner = inv -> {
            if (inv.command().equals(List.of("check-report"))) {
                String content;
                try {
                    content = Files.readString(artifact);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                return content.contains("TESTS_GREEN")
                        ? new ScriptResult(0, "ok", "")
                        : new ScriptResult(1, "", "missing TESTS_GREEN marker");
            }
            return new ScriptResult(0, "ok", "");
        };

        try (PipelineEngine engine = new PipelineEngine(stateStore, agentRuntime, scriptRunner,
                ManifestProjectorPort.NOOP, ScopeDiffPort.NOOP, SecretStorePort.NOOP, outwardActions)) {
            RunId runId = engine.start(project, definition, "feature-x");

            until(() -> engine.snapshot(runId).map(s -> statusOf(s, "gate-deliver") == StepStatus.WAITING_GATE).orElse(false),
                    10_000);
            // FR-5.2/FR-5.3: the gate's own artifacts are the real file on disk, not the model's summary.
            assertThat(Files.readString(artifact)).contains("TESTS_GREEN");
            engine.submit(new EngineCommand.GateAnswered(runId, "gate-deliver", "confirm", "alice", Instant.now()));

            until(() -> engine.snapshot(runId).map(s -> s.status() == RunStatus.COMPLETED).orElse(false), 10_000);

            RunSnapshot snapshot = engine.snapshot(runId).orElseThrow();
            assertThat(statusOf(snapshot, "work")).isEqualTo(StepStatus.PASSED);
            assertThat(statusOf(snapshot, "review")).isEqualTo(StepStatus.PASSED);
            assertThat(statusOf(snapshot, "gate-deliver")).isEqualTo(StepStatus.PASSED);
            assertThat(statusOf(snapshot, "deliver")).isEqualTo(StepStatus.PASSED);
            assertThat(statusOf(snapshot, "rejected")).isEqualTo(StepStatus.SKIPPED);
        }

        assertThat(run(remote, "log", "-1", "--format=%s", "refs/heads/feature-x/deliver").strip())
                .isEqualTo("forgeide: feature-x (deliver)");
        assertThat(run(remote, "show", "refs/heads/feature-x/deliver:out/report.md")).contains("TESTS_GREEN");
        assertThat(prListCalls.get()).isEqualTo(1);
        assertThat(prCreateCalls.get()).isEqualTo(1);
    }

    private static StepStatus statusOf(RunSnapshot snapshot, String stepId) {
        return snapshot.steps().stream().filter(s -> s.stepId().equals(stepId)).findFirst().orElseThrow().status();
    }

    private static void until(BooleanSupplier condition, long timeoutMs) {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within " + timeoutMs + "ms");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void initBareRemote(Path remote) throws IOException, InterruptedException {
        Files.createDirectories(remote);
        run(remote, "init", "-q", "--bare");
    }

    private static void initWorkingRepoWithRemote(Path working, Path remote) throws IOException, InterruptedException {
        Files.createDirectories(working);
        run(working, "init", "-q", ".");
        run(working, "config", "user.email", "test@example.com");
        run(working, "config", "user.name", "Test");
        run(working, "commit", "--allow-empty", "-q", "-m", "initial");
        run(working, "remote", "add", "origin", remote.toString());
    }

    private static void assumeGitAvailable() {
        try {
            Process p = new ProcessBuilder("git", "--version").start();
            assumeTrue(p.waitFor() == 0, "git binary not available");
        } catch (IOException | InterruptedException e) {
            assumeTrue(false, "git binary not available");
        }
    }

    private static String run(Path dir, String... args) throws IOException, InterruptedException {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed with " + exit + ": " + output);
        }
        return output;
    }
}
