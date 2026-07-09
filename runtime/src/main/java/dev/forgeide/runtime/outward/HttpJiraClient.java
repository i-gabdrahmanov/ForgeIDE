package dev.forgeide.runtime.outward;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.forgeide.core.port.OutwardActionException;
import dev.forgeide.core.port.OutwardActionsPort;
import dev.forgeide.core.project.JiraProjectConfig;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Jira REST API v2 ({@code /rest/api/2/issue/{key}/...}) half of {@code OutwardActionsPort}
 * (T17): comment + workflow transition, both made retry-safe the same way {@code create_pr} is —
 * check what already happened before writing anything new.
 */
final class HttpJiraClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http;

    HttpJiraClient(HttpClient http) {
        this.http = http;
    }

    /** Posts {@code comment}, unless an existing comment on the issue already has that exact
     * body — the comment text is fully deterministic (T17: engine-composed, never model text),
     * so an exact match reliably means "this retry already succeeded". */
    OutwardActionsPort.Outcome comment(OutwardActionsPort.JiraCommentRequest request) throws OutwardActionException {
        JiraProjectConfig jira = request.jira();
        String token = request.env().get("JIRA_TOKEN");
        String issuePath = jira.baseUrl() + "/rest/api/2/issue/" + urlEncode(request.issueKey());

        JsonNode existing = HttpApi.send(http, MAPPER,
                HttpRequest.newBuilder(URI.create(issuePath + "/comment")).GET(), token, "Jira comment list");
        for (JsonNode comment : existing.path("comments")) {
            if (comment.path("body").asText("").equals(request.comment())) {
                Map<String, String> refs = new LinkedHashMap<>();
                if (comment.hasNonNull("id")) {
                    refs.put("comment_id", comment.get("id").asText());
                }
                return new OutwardActionsPort.Outcome(refs);
            }
        }

        ObjectNode body = MAPPER.createObjectNode();
        body.put("body", request.comment());
        JsonNode created = HttpApi.send(http, MAPPER, HttpRequest.newBuilder(URI.create(issuePath + "/comment"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)),
                token, "Jira comment create");
        Map<String, String> refs = new LinkedHashMap<>();
        if (created.hasNonNull("id")) {
            refs.put("comment_id", created.get("id").asText());
        }
        return new OutwardActionsPort.Outcome(refs);
    }

    /** Drives the issue through {@link JiraProjectConfig#transitionName()}. If Jira does not
     * currently offer that transition, the issue is treated as already past it — an idempotent
     * success, not an error, which is the shape a retried transition actually takes (the first
     * attempt's transition already landed, so it's off the list the second time around). */
    OutwardActionsPort.Outcome transition(OutwardActionsPort.JiraTransitionRequest request) throws OutwardActionException {
        JiraProjectConfig jira = request.jira();
        String token = request.env().get("JIRA_TOKEN");
        String issuePath = jira.baseUrl() + "/rest/api/2/issue/" + urlEncode(request.issueKey());

        JsonNode available = HttpApi.send(http, MAPPER,
                HttpRequest.newBuilder(URI.create(issuePath + "/transitions")).GET(), token, "Jira transitions list");
        String transitionId = null;
        for (JsonNode transition : available.path("transitions")) {
            if (transition.path("name").asText("").equalsIgnoreCase(jira.transitionName())) {
                transitionId = transition.path("id").asText(null);
                break;
            }
        }
        if (transitionId == null) {
            return new OutwardActionsPort.Outcome(Map.of("jira_transition", "already-applied"));
        }

        ObjectNode body = MAPPER.createObjectNode();
        body.putObject("transition").put("id", transitionId);
        HttpApi.send(http, MAPPER, HttpRequest.newBuilder(URI.create(issuePath + "/transitions"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)),
                token, "Jira transition apply");
        return new OutwardActionsPort.Outcome(Map.of("jira_transition", "applied"));
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
