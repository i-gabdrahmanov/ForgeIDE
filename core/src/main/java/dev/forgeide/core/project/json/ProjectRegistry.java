package dev.forgeide.core.project.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.forgeide.core.project.ProjectDefinition;
import dev.forgeide.core.project.ProjectId;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The project registry persisted at {@code ~/.forgeide/projects.json} (T04 scope; SDD FR-1.1).
 * The whole file is read/rewritten on every mutation — registries are expected to hold at most
 * a handful of projects, so this trades throughput for a dead-simple, always-consistent format.
 * Writes are atomic (temp file + move) so a crash mid-save cannot corrupt the registry.
 */
public final class ProjectRegistry {

    private static final int SCHEMA_VERSION = 1;

    private final Path file;
    private final ObjectMapper mapper;
    private final ProjectJsonCodec codec;

    public ProjectRegistry() {
        this(defaultFile());
    }

    public ProjectRegistry(Path file) {
        this.file = file;
        this.mapper = new ObjectMapper();
        this.codec = new ProjectJsonCodec(mapper);
    }

    public static Path defaultFile() {
        return Path.of(System.getProperty("user.home"), ".forgeide", "projects.json");
    }

    public List<ProjectDefinition> list() {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        JsonNode root = readRoot();
        List<ProjectDefinition> projects = new ArrayList<>();
        root.path("projects").forEach(node -> projects.add(codec.fromNode(node)));
        return projects;
    }

    public Optional<ProjectDefinition> find(ProjectId id) {
        return list().stream().filter(p -> p.id().equals(id)).findFirst();
    }

    /** Inserts or replaces (by id) and persists the whole registry. */
    public ProjectDefinition save(ProjectDefinition project) {
        List<ProjectDefinition> projects = new ArrayList<>(list());
        projects.removeIf(p -> p.id().equals(project.id()));
        projects.add(project);
        writeAll(projects);
        return project;
    }

    public void delete(ProjectId id) {
        List<ProjectDefinition> projects = new ArrayList<>(list());
        if (projects.removeIf(p -> p.id().equals(id))) {
            writeAll(projects);
        }
    }

    private JsonNode readRoot() {
        try {
            return mapper.readTree(file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read project registry: " + file, e);
        }
    }

    private void writeAll(List<ProjectDefinition> projects) {
        ObjectNode root = mapper.createObjectNode();
        root.put("version", SCHEMA_VERSION);
        ArrayNode array = root.putArray("projects");
        projects.forEach(p -> array.add(codec.toNode(p)));

        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = Files.createTempFile(parent, "projects", ".json.tmp");
            Files.writeString(tmp, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                    StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write project registry: " + file, e);
        }
    }
}
