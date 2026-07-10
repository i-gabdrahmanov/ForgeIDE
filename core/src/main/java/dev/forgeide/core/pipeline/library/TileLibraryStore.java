package dev.forgeide.core.pipeline.library;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.StepDefinition;
import dev.forgeide.core.pipeline.yaml.PipelineYaml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Filesystem-backed tile library (FR-2.9), one directory per entry under a {@link
 * LibraryScope#directory}:
 *
 * <pre>
 * &lt;library-dir&gt;/&lt;entry-id&gt;/
 *   meta.json    — {@link LibraryTileMetadata}
 *   steps.yaml   — the entry's steps, written through {@link PipelineYaml} (structural only —
 *                  a lone subgraph is not expected to pass full pipeline validation on its own,
 *                  same reasoning {@code PipelineYaml#serialize} already applies: it never
 *                  validates, only {@code parse} does, and this reads back via the
 *                  non-throwing {@code parseLenient})
 *   files/&lt;path&gt; — every prompt/script file the steps referenced at save time, key = the
 *                  path recorded in {@link LibraryTile#files()}
 * </pre>
 *
 * <p>No file-locking or atomic multi-file commit — this is a local developer convenience store,
 * not run state; a save/delete racing another IDE process is out of scope (same trust level as
 * hand-editing the directory between IDE runs).
 */
public final class TileLibraryStore {

    private static final String META_FILE = "meta.json";
    private static final String STEPS_FILE = "steps.yaml";
    private static final String FILES_DIR = "files";
    private static final String FRAGMENT_ID = "library-entry";

    private final ObjectMapper mapper = new ObjectMapper();
    private final PipelineYaml pipelineYaml = new PipelineYaml();

    public List<LibraryTileMetadata> list(Path libraryDir) {
        if (!Files.isDirectory(libraryDir)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(libraryDir)) {
            List<LibraryTileMetadata> result = new ArrayList<>();
            entries.filter(Files::isDirectory).forEach(dir -> readMetadata(dir).ifPresent(result::add));
            result.sort(Comparator.comparing(LibraryTileMetadata::title, String.CASE_INSENSITIVE_ORDER));
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list library: " + libraryDir, e);
        }
    }

    public Optional<LibraryTile> read(Path libraryDir, String entryId) {
        Path entryDir = libraryDir.resolve(entryId);
        if (!Files.isDirectory(entryDir)) {
            return Optional.empty();
        }
        LibraryTileMetadata metadata = readMetadata(entryDir)
                .orElseThrow(() -> new IllegalStateException("corrupt library entry (no " + META_FILE + "): " + entryDir));
        List<StepDefinition> steps = readSteps(entryDir);
        Map<String, String> files = readFiles(entryDir.resolve(FILES_DIR));
        return Optional.of(new LibraryTile(metadata, steps, files));
    }

    /** Upserts by {@link LibraryTileMetadata#id()} — a re-save under the same id fully replaces
     * the entry's steps/files (stale files from a previous save are removed first). */
    public void save(Path libraryDir, LibraryTile tile) {
        Path entryDir = libraryDir.resolve(tile.metadata().id());
        try {
            Files.createDirectories(entryDir);
            Files.writeString(entryDir.resolve(META_FILE), writeMetadata(tile.metadata()), StandardCharsets.UTF_8);
            PipelineDefinition fragment = new PipelineDefinition(FRAGMENT_ID, 1, tile.steps());
            Files.writeString(entryDir.resolve(STEPS_FILE), pipelineYaml.serialize(fragment), StandardCharsets.UTF_8);

            Path filesDir = entryDir.resolve(FILES_DIR);
            deleteRecursively(filesDir);
            for (Map.Entry<String, String> file : tile.files().entrySet()) {
                Path target = filesDir.resolve(file.getKey()).normalize();
                if (!target.startsWith(filesDir)) {
                    throw new IllegalArgumentException("library file path escapes entry directory: " + file.getKey());
                }
                if (target.getParent() != null) {
                    Files.createDirectories(target.getParent());
                }
                Files.writeString(target, file.getValue(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot save library entry: " + entryDir, e);
        }
    }

    public void delete(Path libraryDir, String entryId) {
        deleteRecursively(libraryDir.resolve(entryId));
    }

    // ---- metadata JSON -----------------------------------------------------------------------

    private Optional<LibraryTileMetadata> readMetadata(Path entryDir) {
        Path metaFile = entryDir.resolve(META_FILE);
        if (!Files.isRegularFile(metaFile)) {
            return Optional.empty();
        }
        try {
            JsonNode node = mapper.readTree(metaFile.toFile());
            List<String> scope = new ArrayList<>();
            node.path("scope").forEach(n -> scope.add(n.asText()));
            Optional<LocalDate> validUntil = node.hasNonNull("validUntil")
                    ? Optional.of(LocalDate.parse(node.get("validUntil").asText()))
                    : Optional.empty();
            return Optional.of(new LibraryTileMetadata(
                    text(node, "id"), text(node, "title"), text(node, "owner"),
                    validUntil, scope, Instant.parse(text(node, "savedAt"))));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + metaFile, e);
        }
    }

    private String writeMetadata(LibraryTileMetadata metadata) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", metadata.id());
        node.put("title", metadata.title());
        node.put("owner", metadata.owner());
        metadata.validUntil().ifPresent(d -> node.put("validUntil", d.toString()));
        ArrayNode scope = node.putArray("scope");
        metadata.scope().forEach(scope::add);
        node.put("savedAt", metadata.savedAt().toString());
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("failed to serialise library metadata", e);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalStateException("library meta.json: missing or non-string field '" + field + "'");
        }
        return value.asText();
    }

    // ---- steps.yaml ---------------------------------------------------------------------------

    private List<StepDefinition> readSteps(Path entryDir) {
        Path stepsFile = entryDir.resolve(STEPS_FILE);
        try {
            String yaml = Files.readString(stepsFile, StandardCharsets.UTF_8);
            return pipelineYaml.parseLenient(yaml).definition()
                    .orElseThrow(() -> new IllegalStateException("corrupt library entry (unparsable " + STEPS_FILE + "): " + entryDir))
                    .steps();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + stepsFile, e);
        }
    }

    // ---- files/ ---------------------------------------------------------------------------

    private Map<String, String> readFiles(Path filesDir) {
        if (!Files.isDirectory(filesDir)) {
            return Map.of();
        }
        try (Stream<Path> walk = Files.walk(filesDir)) {
            Map<String, String> files = new LinkedHashMap<>();
            walk.filter(Files::isRegularFile).forEach(p -> {
                String key = filesDir.relativize(p).toString();
                try {
                    files.put(key, Files.readString(p, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new UncheckedIOException("cannot read library file " + p, e);
                }
            });
            return files;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot walk " + filesDir, e);
        }
    }

    private static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> toDelete = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path p : toDelete) {
                Files.delete(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot delete " + root, e);
        }
    }
}
