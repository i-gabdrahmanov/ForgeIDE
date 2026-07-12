package dev.forgeide.importer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Persists {@link ImportResult#stepToRegistryId()} next to the project's {@code pipeline.yaml}
 * so a later IDE session can rebuild a {@code RegistryTileValidityChecker} without re-running the
 * import (T24: the canvas needs this on every open, not just right after importing).
 */
public record ImportManifest(Map<String, String> stepToRegistryId) {

    private static final String FILE_NAME = "import-manifest.json";

    public ImportManifest {
        stepToRegistryId = Map.copyOf(stepToRegistryId);
    }

    /** {@code <project>/.forgeide/import-manifest.json} — sibling of {@code pipeline.yaml}. */
    public static Path pathUnder(Path forgeideDir) {
        return forgeideDir.resolve(FILE_NAME);
    }

    public static Optional<ImportManifest> readIfPresent(Path file) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            JsonNode root = new ObjectMapper().readTree(file.toFile());
            Map<String, String> mapping = new LinkedHashMap<>();
            root.path("stepToRegistryId").fields()
                    .forEachRemaining(e -> mapping.put(e.getKey(), e.getValue().asText()));
            return Optional.of(new ImportManifest(mapping));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    /** JSON text this manifest would write — exposed so {@link ImportWriter} can diff it against
     * what is already on disk before touching anything (T35). */
    public String toJson() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        ObjectNode mapping = root.putObject("stepToRegistryId");
        stepToRegistryId.forEach(mapping::put);
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot serialize import manifest", e);
        }
    }

    public void write(Path file) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, toJson(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }
}
