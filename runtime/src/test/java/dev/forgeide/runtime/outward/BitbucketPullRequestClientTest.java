package dev.forgeide.runtime.outward;

import com.sun.net.httpserver.HttpServer;
import dev.forgeide.core.port.OutwardActionsPort;
import dev.forgeide.core.project.PrProvider;
import dev.forgeide.core.project.PrRepoConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Mirrors {@link GitHubPullRequestClientTest} against Bitbucket Cloud's REST shape (T17). */
class BitbucketPullRequestClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void createsAPrWhenNoneExistsYet() throws Exception {
        AtomicInteger postCalls = new AtomicInteger();

        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/repositories/acme/demo/pullrequests", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, "{\"values\":[]}");
            } else {
                postCalls.incrementAndGet();
                respond(exchange, 201, "{\"id\":42,\"links\":{\"html\":{\"href\":\"https://bitbucket.org/acme/demo/pull-requests/42\"}}}");
            }
        });
        server.start();

        BitbucketPullRequestClient client = new BitbucketPullRequestClient(HttpClient.newHttpClient());
        PrRepoConfig repo = new PrRepoConfig(PrProvider.BITBUCKET, baseUrl(), "acme/demo");
        OutwardActionsPort.CreatePrRequest request = new OutwardActionsPort.CreatePrRequest(
                Path.of("."), repo, "feature-x/deliver", "main", "forgeide: feature-x", "body", Map.of());

        OutwardActionsPort.Outcome outcome = client.createOrReuse(request);

        assertThat(outcome.resultRefs()).containsEntry("pr_url", "https://bitbucket.org/acme/demo/pull-requests/42")
                .containsEntry("pr_number", "42");
        assertThat(postCalls.get()).isEqualTo(1);
    }

    @Test
    void reusesAnExistingOpenPrInsteadOfCreatingADuplicate() throws Exception {
        AtomicInteger postCalls = new AtomicInteger();

        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/repositories/acme/demo/pullrequests", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, "{\"values\":[{\"id\":7,\"links\":{\"html\":{\"href\":\"https://bitbucket.org/acme/demo/pull-requests/7\"}}}]}");
            } else {
                postCalls.incrementAndGet();
                respond(exchange, 201, "{}");
            }
        });
        server.start();

        BitbucketPullRequestClient client = new BitbucketPullRequestClient(HttpClient.newHttpClient());
        PrRepoConfig repo = new PrRepoConfig(PrProvider.BITBUCKET, baseUrl(), "acme/demo");
        OutwardActionsPort.CreatePrRequest request = new OutwardActionsPort.CreatePrRequest(
                Path.of("."), repo, "feature-x/deliver", "main", "forgeide: feature-x", "body", Map.of());

        OutwardActionsPort.Outcome outcome = client.createOrReuse(request);

        assertThat(outcome.resultRefs()).containsEntry("pr_url", "https://bitbucket.org/acme/demo/pull-requests/7");
        assertThat(postCalls.get()).isZero();
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
