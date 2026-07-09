package dev.forgeide.runtime.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Persists the deployed/accepted harness baseline (SDD SR-8) at {@link
 * HarnessLayout#registryFile} — the per-project record {@link DefaultHarnessGuard} compares a
 * fresh {@link HarnessManifest#scan} against to detect drift, across IDE restarts.
 */
final class HarnessRegistry {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HarnessRegistry() {
    }

    static Optional<Entry> read(Path forgeideHome, Path projectRoot) {
        Path file = HarnessLayout.registryFile(forgeideHome, projectRoot);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            JsonNode root = MAPPER.readTree(file.toFile());
            Map<String, String> manifest = new TreeMap<>();
            JsonNode manifestNode = root.path("manifest");
            manifestNode.fields().forEachRemaining(e -> manifest.put(e.getKey(), e.getValue().asText()));
            return Optional.of(new Entry(manifest, root.path("hash").asText(""),
                    root.path("preflightPassed").asBoolean(false), root.path("preflightOutput").asText(""),
                    Instant.parse(root.path("deployedAt").asText(Instant.EPOCH.toString()))));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    static void write(Path forgeideHome, Path projectRoot, Entry entry) {
        Path file = HarnessLayout.registryFile(forgeideHome, projectRoot);
        ObjectNode root = MAPPER.createObjectNode();
        root.put("hash", entry.hash());
        root.put("preflightPassed", entry.preflightPassed());
        root.put("preflightOutput", entry.preflightOutput());
        root.put("deployedAt", entry.deployedAt().toString());
        ObjectNode manifestNode = root.putObject("manifest");
        new TreeMap<>(entry.manifest()).forEach(manifestNode::put);
        try {
            Files.createDirectories(file.getParent());
            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
            Path tmp = Files.createTempFile(file.getParent(), "registry", ".json.tmp");
            try {
                Files.writeString(tmp, json, StandardCharsets.UTF_8);
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write harness registry: " + file, e);
        }
    }

    record Entry(Map<String, String> manifest, String hash, boolean preflightPassed, String preflightOutput,
                 Instant deployedAt) {
        Entry {
            manifest = new LinkedHashMap<>(manifest);
        }
    }
}
