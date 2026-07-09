package dev.forgeide.runtime.state;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.forgeide.core.port.ManifestProjectorPort;
import dev.forgeide.core.run.RunSnapshot;
import dev.forgeide.core.run.StepSnapshot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * File-backed {@link ManifestProjectorPort} (SD §4, SDD FR-7.2/SR-2/Т-1): writes the {@code
 * pipeline-state} Forge projection to {@code <project>/ground/statements/<pipeline>/<feature>/
 * manifest.json} before every agent phase, and re-hashes it after to catch a write that landed
 * there through anything other than this class (a model editing its own manifest to close out
 * steps, past a state-write-guard hook that missed it).
 *
 * <p>Hashing reuses {@link CanonicalJson} (SDD §5.3's canonicalization: sorted keys, no
 * insignificant whitespace) so the comparison is insensitive to formatting-only differences and
 * only trips on an actual content change — the same primitive {@link FileStateStore} uses for
 * {@code run.json}'s checksum.
 */
public final class ManifestProjector implements ManifestProjectorPort {

    private static final int MAX_DIFF_LINES = 20;
    private static final int MAX_DIFF_LINE_LENGTH = 300;

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String project(Path projectRoot, String pipelineId, String featureSlug, RunSnapshot snapshot) {
        ObjectNode node = toManifestNode(projectRoot, pipelineId, featureSlug, snapshot);
        writeManifest(manifestPath(projectRoot, pipelineId, featureSlug), node);
        return hashOf(node);
    }

    @Override
    public Optional<String> verifyAndRestore(Path projectRoot, String pipelineId, String featureSlug,
                                              RunSnapshot snapshot, String expectedHash) {
        Path path = manifestPath(projectRoot, pipelineId, featureSlug);
        Optional<JsonNode> actual = readManifest(path);
        String actualHash = actual.map(this::hashOf).orElse("");
        if (actualHash.equals(expectedHash)) {
            return Optional.empty();
        }

        String actualText = actual.map(this::prettyOf)
                .orElse("(manifest.json missing or not valid JSON)");
        ObjectNode restoredNode = toManifestNode(projectRoot, pipelineId, featureSlug, snapshot);
        writeManifest(path, restoredNode);
        return Optional.of(diffSummary(actualText, prettyOf(restoredNode)));
    }

    @Override
    public Optional<ObjectNode> readOrigin(Path projectRoot, String pipelineId, String featureSlug, String stepId) {
        Path path = originPath(projectRoot, pipelineId, featureSlug, stepId);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            JsonNode node = mapper.readTree(path.toFile());
            return node instanceof ObjectNode objectNode ? Optional.of(objectNode) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            // Best-effort evidence read (T15 scope) — a corrupt/partial origin file must not
            // fail the phase it is only meant to annotate.
            return Optional.empty();
        }
    }

    // ---- layout -------------------------------------------------------------------------

    static Path statementsDir(Path projectRoot, String pipelineId, String featureSlug) {
        return projectRoot.resolve("ground").resolve("statements").resolve(pipelineId).resolve(featureSlug);
    }

    static Path manifestPath(Path projectRoot, String pipelineId, String featureSlug) {
        return statementsDir(projectRoot, pipelineId, featureSlug).resolve("manifest.json");
    }

    static Path originPath(Path projectRoot, String pipelineId, String featureSlug, String stepId) {
        return statementsDir(projectRoot, pipelineId, featureSlug).resolve("_origins").resolve(stepId + ".json");
    }

    // ---- projection content ---------------------------------------------------------------

    /** {@code pipeline-state} Forge shape (SKILL.md): only {@code steps[].id}/{@code status} are
     * actually read by the hooks (risk_ladder's {@code manifest_status}) — the rest is the
     * minimum envelope real consumers expect a manifest to carry, without fabricating fields
     * (titles, artifacts, timestamps) this projection has no truthful value for. */
    private ObjectNode toManifestNode(Path projectRoot, String pipelineId, String featureSlug, RunSnapshot snapshot) {
        ObjectNode root = mapper.createObjectNode();
        root.put("version", 1);
        root.put("skill", pipelineId);
        root.put("feature", featureSlug);
        root.put("project_root", projectRoot.toString());
        root.put("last_update", Instant.now().toString());
        ArrayNode steps = root.putArray("steps");
        for (StepSnapshot step : snapshot.steps()) {
            ObjectNode stepNode = steps.addObject();
            stepNode.put("id", step.stepId());
            stepNode.put("status", ManifestStatus.of(step.status()));
        }
        return root;
    }

    private String hashOf(JsonNode node) {
        return CanonicalJson.sha256Hex("", CanonicalJson.canonicalBytes(node));
    }

    private String prettyOf(JsonNode node) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- I/O ------------------------------------------------------------------------------

    private void writeManifest(Path target, JsonNode node) {
        try {
            Files.createDirectories(target.getParent());
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
            Path tmp = Files.createTempFile(target.getParent(), "manifest", ".json.tmp");
            try {
                Files.writeString(tmp, json, StandardCharsets.UTF_8);
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write manifest projection: " + target, e);
        }
    }

    private Optional<JsonNode> readManifest(Path path) {
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readTree(path.toFile()));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    // ---- diff (for the incident.tamper audit payload) --------------------------------------

    private static String diffSummary(String before, String after) {
        List<String> a = List.of(before.split("\n", -1));
        List<String> b = List.of(after.split("\n", -1));

        int prefix = 0;
        while (prefix < a.size() && prefix < b.size() && a.get(prefix).equals(b.get(prefix))) {
            prefix++;
        }
        int endA = a.size();
        int endB = b.size();
        while (endA > prefix && endB > prefix && a.get(endA - 1).equals(b.get(endB - 1))) {
            endA--;
            endB--;
        }
        List<String> removed = a.subList(prefix, endA);
        List<String> added = b.subList(prefix, endB);

        StringBuilder sb = new StringBuilder();
        sb.append("manifest tampered — actual (on disk) vs restored (from SoT), from line ")
                .append(prefix + 1).append(": -").append(removed.size()).append("/+").append(added.size())
                .append(" line(s)\n");
        removed.stream().limit(MAX_DIFF_LINES).forEach(l -> sb.append("- ").append(truncate(l)).append('\n'));
        added.stream().limit(MAX_DIFF_LINES).forEach(l -> sb.append("+ ").append(truncate(l)).append('\n'));
        return sb.toString();
    }

    private static String truncate(String line) {
        return line.length() > MAX_DIFF_LINE_LENGTH ? line.substring(0, MAX_DIFF_LINE_LENGTH) + "…" : line;
    }
}
