package dev.forgeide.runtime.outward;

import com.sun.net.httpserver.HttpServer;
import dev.forgeide.core.port.OutwardActionsPort;
import dev.forgeide.core.project.JiraProjectConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** T17: Jira comment/transition, made retry-safe by checking what already happened first. */
class HttpJiraClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void postsANewCommentWhenNoIdenticalOneExists() throws Exception {
        AtomicInteger postCalls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/rest/api/2/issue/DEMO-1/comment", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, "{\"comments\":[{\"id\":\"1\",\"body\":\"unrelated\"}]}");
            } else {
                postCalls.incrementAndGet();
                respond(exchange, 201, "{\"id\":\"55\"}");
            }
        });
        server.start();

        HttpJiraClient client = new HttpJiraClient(HttpClient.newHttpClient());
        JiraProjectConfig jira = new JiraProjectConfig(baseUrl(), "Done");
        OutwardActionsPort.Outcome outcome = client.comment(
                new OutwardActionsPort.JiraCommentRequest(jira, "DEMO-1", "delivered.", Map.of()));

        assertThat(outcome.resultRefs()).containsEntry("comment_id", "55");
        assertThat(postCalls.get()).isEqualTo(1);
    }

    @Test
    void skipsPostingWhenAnIdenticalCommentAlreadyExists() throws Exception {
        AtomicInteger postCalls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/rest/api/2/issue/DEMO-1/comment", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, "{\"comments\":[{\"id\":\"9\",\"body\":\"delivered.\"}]}");
            } else {
                postCalls.incrementAndGet();
                respond(exchange, 201, "{\"id\":\"999\"}");
            }
        });
        server.start();

        HttpJiraClient client = new HttpJiraClient(HttpClient.newHttpClient());
        JiraProjectConfig jira = new JiraProjectConfig(baseUrl(), "Done");
        OutwardActionsPort.Outcome outcome = client.comment(
                new OutwardActionsPort.JiraCommentRequest(jira, "DEMO-1", "delivered.", Map.of()));

        assertThat(outcome.resultRefs()).containsEntry("comment_id", "9");
        assertThat(postCalls.get()).isZero();
    }

    @Test
    void appliesTheConfiguredTransitionWhenOffered() throws Exception {
        AtomicInteger postCalls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/rest/api/2/issue/DEMO-1/transitions", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, "{\"transitions\":[{\"id\":\"31\",\"name\":\"Done\"}]}");
            } else {
                postCalls.incrementAndGet();
                respond(exchange, 204, "");
            }
        });
        server.start();

        HttpJiraClient client = new HttpJiraClient(HttpClient.newHttpClient());
        JiraProjectConfig jira = new JiraProjectConfig(baseUrl(), "Done");
        OutwardActionsPort.Outcome outcome = client.transition(
                new OutwardActionsPort.JiraTransitionRequest(jira, "DEMO-1", Map.of()));

        assertThat(outcome.resultRefs()).containsEntry("jira_transition", "applied");
        assertThat(postCalls.get()).isEqualTo(1);
    }

    @Test
    void treatsAnUnavailableTransitionAsAlreadyApplied() throws Exception {
        AtomicInteger postCalls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/rest/api/2/issue/DEMO-1/transitions", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, "{\"transitions\":[{\"id\":\"12\",\"name\":\"Start Review\"}]}");
            } else {
                postCalls.incrementAndGet();
                respond(exchange, 204, "");
            }
        });
        server.start();

        HttpJiraClient client = new HttpJiraClient(HttpClient.newHttpClient());
        JiraProjectConfig jira = new JiraProjectConfig(baseUrl(), "Done");
        OutwardActionsPort.Outcome outcome = client.transition(
                new OutwardActionsPort.JiraTransitionRequest(jira, "DEMO-1", Map.of()));

        assertThat(outcome.resultRefs()).containsEntry("jira_transition", "already-applied");
        assertThat(postCalls.get()).isZero();
    }

    private String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws java.io.IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        if (body.isEmpty()) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }
}
