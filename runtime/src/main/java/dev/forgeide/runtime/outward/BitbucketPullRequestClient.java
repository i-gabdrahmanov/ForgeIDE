package dev.forgeide.runtime.outward;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.forgeide.core.port.OutwardActionException;
import dev.forgeide.core.port.OutwardActionsPort;
import dev.forgeide.core.project.PrRepoConfig;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bitbucket Cloud REST v2.0 (`/repositories/{workspace}/{repo}/pullrequests`) {@link
 * PullRequestClient} (T17). */
final class BitbucketPullRequestClient implements PullRequestClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http;

    BitbucketPullRequestClient(HttpClient http) {
        this.http = http;
    }

    @Override
    public OutwardActionsPort.Outcome createOrReuse(OutwardActionsPort.CreatePrRequest request) throws OutwardActionException {
        PrRepoConfig repo = request.repo();
        String token = request.env().get("GIT_TOKEN");

        String query = "state=\"OPEN\" AND source.branch.name=\"" + request.sourceBranch() + "\"";
        String listUrl = repo.apiBaseUrl() + "/repositories/" + repo.repoSlug() + "/pullrequests?q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8);
        JsonNode existing = HttpApi.send(http, MAPPER,
                HttpRequest.newBuilder(URI.create(listUrl)).GET(), token, "Bitbucket pullrequests list");
        JsonNode values = existing.path("values");
        if (values.isArray() && !values.isEmpty()) {
            return outcomeOf(values.get(0));
        }

        ObjectNode body = MAPPER.createObjectNode();
        body.put("title", request.title());
        body.put("description", request.body());
        body.putObject("source").putObject("branch").put("name", request.sourceBranch());
        body.putObject("destination").putObject("branch").put("name", request.targetBranch());
        String createUrl = repo.apiBaseUrl() + "/repositories/" + repo.repoSlug() + "/pullrequests";
        JsonNode created = HttpApi.send(http, MAPPER, HttpRequest.newBuilder(URI.create(createUrl))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)),
                token, "Bitbucket pullrequest create");
        return outcomeOf(created);
    }

    private static OutwardActionsPort.Outcome outcomeOf(JsonNode pr) {
        Map<String, String> refs = new LinkedHashMap<>();
        JsonNode html = pr.path("links").path("html").path("href");
        if (html.isTextual()) {
            refs.put("pr_url", html.asText());
        }
        if (pr.hasNonNull("id")) {
            refs.put("pr_number", String.valueOf(pr.get("id").asInt()));
        }
        return new OutwardActionsPort.Outcome(refs);
    }
}
