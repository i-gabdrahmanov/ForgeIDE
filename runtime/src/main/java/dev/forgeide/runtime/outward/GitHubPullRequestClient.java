package dev.forgeide.runtime.outward;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.forgeide.core.port.OutwardActionException;
import dev.forgeide.core.port.OutwardActionsPort;
import dev.forgeide.core.project.PrRepoConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** GitHub REST v3 (`/repos/{owner}/{repo}/pulls`) {@link PullRequestClient} (T17). */
final class GitHubPullRequestClient implements PullRequestClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http;

    GitHubPullRequestClient(HttpClient http) {
        this.http = http;
    }

    @Override
    public OutwardActionsPort.Outcome createOrReuse(OutwardActionsPort.CreatePrRequest request) throws OutwardActionException {
        PrRepoConfig repo = request.repo();
        String token = request.env().get("GIT_TOKEN");
        String owner = ownerOf(repo.repoSlug());

        String listUrl = repo.apiBaseUrl() + "/repos/" + repo.repoSlug() + "/pulls?state=open&head="
                + owner + ":" + request.sourceBranch() + "&base=" + request.targetBranch();
        JsonNode existing = HttpApi.send(http, MAPPER,
                HttpRequest.newBuilder(URI.create(listUrl)).GET(), token, "GitHub pulls list");
        if (existing.isArray() && !existing.isEmpty()) {
            return outcomeOf(existing.get(0));
        }

        ObjectNode body = MAPPER.createObjectNode();
        body.put("title", request.title());
        body.put("body", request.body());
        body.put("head", request.sourceBranch());
        body.put("base", request.targetBranch());
        String createUrl = repo.apiBaseUrl() + "/repos/" + repo.repoSlug() + "/pulls";
        JsonNode created = HttpApi.send(http, MAPPER, HttpRequest.newBuilder(URI.create(createUrl))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)),
                token, "GitHub pull create");
        return outcomeOf(created);
    }

    private static String ownerOf(String repoSlug) {
        int slash = repoSlug.indexOf('/');
        return slash >= 0 ? repoSlug.substring(0, slash) : repoSlug;
    }

    private static OutwardActionsPort.Outcome outcomeOf(JsonNode pr) {
        Map<String, String> refs = new LinkedHashMap<>();
        if (pr.hasNonNull("html_url")) {
            refs.put("pr_url", pr.get("html_url").asText());
        }
        if (pr.hasNonNull("number")) {
            refs.put("pr_number", String.valueOf(pr.get("number").asInt()));
        }
        return new OutwardActionsPort.Outcome(refs);
    }
}
