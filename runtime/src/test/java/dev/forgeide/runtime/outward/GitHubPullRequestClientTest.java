package dev.forgeide.runtime.outward;

import com.sun.net.httpserver.HttpServer;
import dev.forgeide.core.port.OutwardActionException;
import dev.forgeide.core.port.OutwardActionsPort;
import dev.forgeide.core.project.PrProvider;
import dev.forgeide.core.project.PrRepoConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T17 acceptance: "повторный запуск outward-шага после сбоя сети не создаёт дубликат PR" —
 * exercised here against a fake GitHub REST server so no live network/credentials are needed.
 */
class GitHubPullRequestClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void createsAPrWhenNoneExistsYetAndSendsTheBearerToken() throws Exception {
        AtomicInteger getCalls = new AtomicInteger();
        AtomicInteger postCalls = new AtomicInteger();
        AtomicReference<String> authHeaderSeen = new AtomicReference<>();

        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/repos/acme/demo/pulls", exchange -> {
            authHeaderSeen.set(exchange.getRequestHeaders().getFirst("Authorization"));
            if ("GET".equals(exchange.getRequestMethod())) {
                getCalls.incrementAndGet();
                respond(exchange, 200, "[]");
            } else {
                postCalls.incrementAndGet();
                respond(exchange, 201, "{\"html_url\":\"https://github.com/acme/demo/pull/7\",\"number\":7}");
            }
        });
        server.start();

        GitHubPullRequestClient client = new GitHubPullRequestClient(HttpClient.newHttpClient());
        PrRepoConfig repo = new PrRepoConfig(PrProvider.GITHUB, baseUrl(), "acme/demo");
        OutwardActionsPort.CreatePrRequest request = new OutwardActionsPort.CreatePrRequest(
                java.nio.file.Path.of("."), repo, "feature-x/deliver", "main", "forgeide: feature-x", "body",
                Map.of("GIT_TOKEN", "s3cr3t"));

        OutwardActionsPort.Outcome outcome = client.createOrReuse(request);

        assertThat(outcome.resultRefs()).containsEntry("pr_url", "https://github.com/acme/demo/pull/7")
                .containsEntry("pr_number", "7");
        assertThat(getCalls.get()).isEqualTo(1);
        assertThat(postCalls.get()).isEqualTo(1);
        assertThat(authHeaderSeen.get()).isEqualTo("Bearer s3cr3t");
    }

    @Test
    void reusesAnExistingOpenPrInsteadOfCreatingADuplicate() throws Exception {
        AtomicInteger postCalls = new AtomicInteger();

        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/repos/acme/demo/pulls", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, "[{\"html_url\":\"https://github.com/acme/demo/pull/3\",\"number\":3}]");
            } else {
                postCalls.incrementAndGet();
                respond(exchange, 201, "{\"html_url\":\"https://github.com/acme/demo/pull/999\",\"number\":999}");
            }
        });
        server.start();

        GitHubPullRequestClient client = new GitHubPullRequestClient(HttpClient.newHttpClient());
        PrRepoConfig repo = new PrRepoConfig(PrProvider.GITHUB, baseUrl(), "acme/demo");
        OutwardActionsPort.CreatePrRequest request = new OutwardActionsPort.CreatePrRequest(
                java.nio.file.Path.of("."), repo, "feature-x/deliver", "main", "forgeide: feature-x", "body", Map.of());

        OutwardActionsPort.Outcome outcome = client.createOrReuse(request);

        assertThat(outcome.resultRefs()).containsEntry("pr_url", "https://github.com/acme/demo/pull/3");
        assertThat(postCalls.get()).isZero();
    }

    @Test
    void aNon2xxResponseBecomesAnOutwardActionException() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/repos/acme/demo/pulls", exchange -> respond(exchange, 503, "{\"message\":\"down\"}"));
        server.start();

        GitHubPullRequestClient client = new GitHubPullRequestClient(HttpClient.newHttpClient());
        PrRepoConfig repo = new PrRepoConfig(PrProvider.GITHUB, baseUrl(), "acme/demo");
        OutwardActionsPort.CreatePrRequest request = new OutwardActionsPort.CreatePrRequest(
                java.nio.file.Path.of("."), repo, "feature-x/deliver", "main", "t", "b", Map.of());

        assertThatThrownBy(() -> client.createOrReuse(request))
                .isInstanceOf(OutwardActionException.class)
                .hasMessageContaining("503");
    }

    private String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

}
