package dev.forgeide.core.project.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.forgeide.core.project.CriticalityProfile;
import dev.forgeide.core.project.JiraProjectConfig;
import dev.forgeide.core.project.OutwardConfig;
import dev.forgeide.core.project.PrProvider;
import dev.forgeide.core.project.PrRepoConfig;
import dev.forgeide.core.project.ProjectDefinition;
import dev.forgeide.core.project.ProjectId;
import dev.forgeide.core.project.RuntimeBinding;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Maps {@link ProjectDefinition} onto the JSON tree stored in {@code ~/.forgeide/projects.json}
 * (T04 scope). Field-by-field, mirroring {@code pipeline.yaml}'s parser/writer split, so the
 * registry stays a plain, inspectable file a user could hand-edit if the IDE is unavailable.
 */
final class ProjectJsonCodec {

    private final ObjectMapper mapper;

    ProjectJsonCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    ObjectNode toNode(ProjectDefinition project) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", project.id().value());
        node.put("name", project.name());
        node.put("repositoryPath", project.repositoryPath().toString());
        ArrayNode specPaths = node.putArray("specPaths");
        project.specPaths().forEach(p -> specPaths.add(p.toString()));
        ObjectNode paramValues = node.putObject("paramValues");
        project.paramValues().forEach(paramValues::put);
        node.put("criticality", project.criticality().name());
        ArrayNode runtimes = node.putArray("runtimes");
        project.runtimes().forEach(r -> runtimes.add(runtimeNode(r)));
        node.set("outward", outwardNode(project.outward()));
        return node;
    }

    private ObjectNode outwardNode(OutwardConfig outward) {
        ObjectNode node = mapper.createObjectNode();
        node.put("gitRemote", outward.gitRemote());
        node.put("targetBranch", outward.targetBranch());
        outward.pr().ifPresent(pr -> {
            ObjectNode prNode = node.putObject("pr");
            prNode.put("provider", pr.provider().name());
            prNode.put("apiBaseUrl", pr.apiBaseUrl());
            prNode.put("repoSlug", pr.repoSlug());
        });
        outward.jira().ifPresent(jira -> {
            ObjectNode jiraNode = node.putObject("jira");
            jiraNode.put("baseUrl", jira.baseUrl());
            jiraNode.put("transitionName", jira.transitionName());
        });
        return node;
    }

    private ObjectNode runtimeNode(RuntimeBinding runtime) {
        ObjectNode node = mapper.createObjectNode();
        node.put("name", runtime.name());
        node.put("binaryPath", runtime.binaryPath().toString());
        ArrayNode flags = node.putArray("flags");
        runtime.flags().forEach(flags::add);
        return node;
    }

    ProjectDefinition fromNode(JsonNode node) {
        ProjectId id = new ProjectId(text(node, "id"));
        String name = text(node, "name");
        Path repositoryPath = Path.of(text(node, "repositoryPath"));
        List<Path> specPaths = new ArrayList<>();
        node.path("specPaths").forEach(n -> specPaths.add(Path.of(n.asText())));
        Map<String, String> paramValues = new LinkedHashMap<>();
        node.path("paramValues").fields().forEachRemaining(e -> paramValues.put(e.getKey(), e.getValue().asText()));
        CriticalityProfile criticality = CriticalityProfile.valueOf(text(node, "criticality"));
        List<RuntimeBinding> runtimes = new ArrayList<>();
        node.path("runtimes").forEach(n -> runtimes.add(runtimeFromNode(n)));
        OutwardConfig outward = node.has("outward") ? outwardFromNode(node.get("outward")) : OutwardConfig.EMPTY;
        return new ProjectDefinition(id, name, repositoryPath, specPaths, paramValues, criticality, runtimes, outward);
    }

    /** {@code outward} was added in T17; a registry file written before then simply has no such
     * field, and {@link #fromNode} above already falls back to {@link OutwardConfig#EMPTY}. */
    private OutwardConfig outwardFromNode(JsonNode node) {
        String gitRemote = text(node, "gitRemote");
        String targetBranch = text(node, "targetBranch");
        Optional<PrRepoConfig> pr = Optional.empty();
        if (node.has("pr")) {
            JsonNode prNode = node.get("pr");
            pr = Optional.of(new PrRepoConfig(PrProvider.valueOf(text(prNode, "provider")),
                    text(prNode, "apiBaseUrl"), text(prNode, "repoSlug")));
        }
        Optional<JiraProjectConfig> jira = Optional.empty();
        if (node.has("jira")) {
            JsonNode jiraNode = node.get("jira");
            jira = Optional.of(new JiraProjectConfig(text(jiraNode, "baseUrl"), text(jiraNode, "transitionName")));
        }
        return new OutwardConfig(gitRemote, targetBranch, pr, jira);
    }

    private RuntimeBinding runtimeFromNode(JsonNode node) {
        String name = text(node, "name");
        Path binaryPath = Path.of(text(node, "binaryPath"));
        List<String> flags = new ArrayList<>();
        node.path("flags").forEach(n -> flags.add(n.asText()));
        return new RuntimeBinding(name, binaryPath, flags);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalStateException("projects.json: missing or non-string field '" + field + "' in " + node);
        }
        return value.asText();
    }
}
