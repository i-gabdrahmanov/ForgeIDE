package dev.forgeide.runtime.outward;

import com.sun.net.httpserver.HttpServer;
import dev.forgeide.core.engine.PipelineEngine;
import dev.forgeide.core.event.EngineCommand;
import dev.forgeide.core.pipeline.BranchStep;
import dev.forgeide.core.pipeline.GateStep;
import dev.forgeide.core.pipeline.OutwardAction;
import dev.forgeide.core.pipeline.OutwardStep;
import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.ScriptStep;
import dev.forgeide.core.port.ManifestProjectorPort;
import dev.forgeide.core.port.ScopeDiffPort;
import dev.forgeide.core.port.SecretStorePort;
import dev.forgeide.core.project.CriticalityProfile;
import dev.forgeide.core.project.OutwardConfig;
import dev.forgeide.core.project.PrProvider;
import dev.forgeide.core.project.PrRepoConfig;
import dev.forgeide.core.project.ProjectDefinition;
import dev.forgeide.core.project.ProjectId;
import dev.forgeide.core.run.RunId;
import dev.forgeide.core.run.RunSnapshot;
import dev.forgeide.core.run.RunStatus;
import dev.forgeide.core.run.StepStatus;
import dev.forgeide.runtime.state.FileStateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
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
 * T17 headline acceptance: "e2e на тестовом репозитории: ветка+push+PR создаются движком после
 * подтверждения гейта" — the full stack, real {@link PipelineEngine}, real git plumbing
 * ({@link GitCliOutwardActions} against a bare repo standing in for the hosted remote), and a
 * fake GitHub server standing in for the PR host. Nothing here is a fixture port; this is the
 * one test that proves the wiring, not just each piece in isolation.
 */
class PipelineEngineOutwardE2ETest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void gateConfirmationDeliversABranchPushAndAPrThroughTheRealEngine(
            @TempDir Path base, @TempDir Path stateDir) throws IOException, InterruptedException {
        assumeGitAvailable();

        Path remote = base.resolve("remote.git");
        Path repo = base.resolve("repo");
        initBareRemote(remote);
        initWorkingRepoWithRemote(repo, remote);
        // Stand-in for what an earlier agent phase would have left uncommitted.
        Files.writeString(repo.resolve("delivered.txt"), "the feature\n");

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

        GateStep gate = new GateStep("gate-deliver", List.of(), "Доставить?", List.of("confirm", "reject"), List.of());
        BranchStep branch = new BranchStep("branch", List.of("gate-deliver"),
                Map.of("confirm", "deliver", "reject", "rejected"));
        OutwardStep deliver = new OutwardStep("deliver", List.of("branch"),
                List.of(OutwardAction.GIT_PUSH, OutwardAction.CREATE_PR), List.of());
        ScriptStep rejected = new ScriptStep("rejected", List.of("branch"), List.of("true"), Duration.ofSeconds(5));
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(gate, branch, deliver, rejected));

        OutwardConfig outwardConfig = new OutwardConfig("origin", "main",
                Optional.of(new PrRepoConfig(PrProvider.GITHUB, "http://localhost:" + server.getAddress().getPort(), "acme/demo")),
                Optional.empty());
        ProjectDefinition project = new ProjectDefinition(ProjectId.newId(), "e2e-test", repo, List.of(), Map.of(),
                CriticalityProfile.LOW, List.of(), outwardConfig);

        FileStateStore stateStore = new FileStateStore(stateDir);
        DefaultOutwardActionsPort outwardActions = new DefaultOutwardActionsPort(HttpClient.newHttpClient());

        try (PipelineEngine engine = new PipelineEngine(stateStore, (inv, onEvent) -> {
            throw new AssertionError("no agent steps in this pipeline");
        }, inv -> new dev.forgeide.core.port.ScriptResult(0, "ok", ""), ManifestProjectorPort.NOOP,
                ScopeDiffPort.NOOP, SecretStorePort.NOOP, outwardActions)) {
            RunId runId = engine.start(project, definition, "feature-x");

            until(() -> engine.snapshot(runId).map(s -> statusOf(s, "gate-deliver") == StepStatus.WAITING_GATE).orElse(false));
            engine.submit(new EngineCommand.GateAnswered(runId, "gate-deliver", "confirm", "alice", Instant.now()));

            until(() -> engine.snapshot(runId).map(s -> s.status() == RunStatus.COMPLETED).orElse(false), 10_000);

            RunSnapshot snapshot = engine.snapshot(runId).orElseThrow();
            assertThat(statusOf(snapshot, "deliver")).isEqualTo(StepStatus.PASSED);
            assertThat(statusOf(snapshot, "rejected")).isEqualTo(StepStatus.SKIPPED);
        }

        assertThat(run(remote, "log", "-1", "--format=%s", "refs/heads/feature-x/deliver").strip())
                .isEqualTo("forgeide: feature-x (deliver)");
        assertThat(run(remote, "show", "refs/heads/feature-x/deliver:delivered.txt")).contains("the feature");
        assertThat(prListCalls.get()).isEqualTo(1);
        assertThat(prCreateCalls.get()).isEqualTo(1);
    }

    private static StepStatus statusOf(RunSnapshot snapshot, String stepId) {
        return snapshot.steps().stream().filter(s -> s.stepId().equals(stepId)).findFirst().orElseThrow().status();
    }

    private static void until(BooleanSupplier condition) {
        until(condition, 2_000);
    }

    private static void until(BooleanSupplier condition, long timeoutMs) {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within " + timeoutMs + "ms");
            }
            try {
                Thread.sleep(5);
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
