package dev.forgeide.runtime.secret;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.forgeide.core.port.SecretStorePort;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * File-backed {@link SecretStorePort} (SDD SR-5/Т-11): git tokens, MCP credentials, and any other
 * per-step secret live at {@code ~/.forgeide/secrets.json}, mode 600 wherever POSIX permissions
 * are available (macOS/Linux — NFR-5; a best-effort no-op on filesystems that don't support
 * them). {@link #resolve} never returns more than the requested {@code env_scope} keys — a step
 * with an empty scope, or one whose keys have no stored value, gets nothing back.
 *
 * <p>Whole-file read/rewrite on every mutation, same trade-off as {@code ProjectRegistry} (T04):
 * a handful of secrets, not thousands, so simplicity wins over throughput. Writes are atomic
 * (temp file + move) so a crash mid-save cannot corrupt the store.
 */
public final class FileSecretStore implements SecretStorePort {

    private static final Set<PosixFilePermission> OWNER_READ_WRITE = PosixFilePermissions.fromString("rw-------");

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();

    public FileSecretStore() {
        this(defaultFile());
    }

    public FileSecretStore(Path file) {
        this.file = file;
    }

    public static Path defaultFile() {
        return Path.of(System.getProperty("user.home"), ".forgeide", "secrets.json");
    }

    @Override
    public Map<String, String> resolve(List<String> envScope) {
        if (envScope.isEmpty()) {
            return Map.of();
        }
        Map<String, String> all = readAll();
        Map<String, String> resolved = new LinkedHashMap<>();
        for (String key : envScope) {
            String value = all.get(key);
            if (value != null) {
                resolved.put(key, value);
            }
        }
        return resolved;
    }

    /** Stores/overwrites one secret. */
    public void put(String key, String value) {
        Map<String, String> all = new LinkedHashMap<>(readAll());
        all.put(key, value);
        writeAll(all);
    }

    public void remove(String key) {
        Map<String, String> all = new LinkedHashMap<>(readAll());
        if (all.remove(key) != null) {
            writeAll(all);
        }
    }

    private Map<String, String> readAll() {
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        JsonNode root;
        try {
            root = mapper.readTree(file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read secret store: " + file, e);
        }
        Map<String, String> all = new LinkedHashMap<>();
        root.fields().forEachRemaining(e -> {
            if (e.getValue().isTextual()) {
                all.put(e.getKey(), e.getValue().asText());
            }
        });
        return all;
    }

    private void writeAll(Map<String, String> all) {
        ObjectNode root = mapper.createObjectNode();
        all.forEach(root::put);
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = Files.createTempFile(parent, "secrets", ".json.tmp");
            Files.writeString(tmp, root.toPrettyString(), StandardCharsets.UTF_8);
            restrictPermissions(tmp);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            restrictPermissions(file);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write secret store: " + file, e);
        }
    }

    private static void restrictPermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, OWNER_READ_WRITE);
        } catch (UnsupportedOperationException | IOException e) {
            // Non-POSIX filesystem (Windows — NFR-5's post-MVP platform): best-effort only.
        }
    }
}
