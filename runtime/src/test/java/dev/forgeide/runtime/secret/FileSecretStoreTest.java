package dev.forgeide.runtime.secret;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** T16 acceptance (SR-5/Т-11): resolves only the requested {@code env_scope} keys, and stores
 * them at mode 600 wherever POSIX permissions are available. */
class FileSecretStoreTest {

    @Test
    void resolvesOnlyTheRequestedKeys(@TempDir Path dir) {
        FileSecretStore store = new FileSecretStore(dir.resolve("secrets.json"));
        store.put("GIT_TOKEN", "s3cr3t");
        store.put("MCP_KEY", "other");

        assertThat(store.resolve(List.of("GIT_TOKEN"))).containsExactly(Map.entry("GIT_TOKEN", "s3cr3t"));
        assertThat(store.resolve(List.of())).isEmpty();
    }

    @Test
    void aKeyWithNoStoredSecretIsSimplyAbsent(@TempDir Path dir) {
        FileSecretStore store = new FileSecretStore(dir.resolve("secrets.json"));
        store.put("GIT_TOKEN", "s3cr3t");

        assertThat(store.resolve(List.of("GIT_TOKEN", "UNKNOWN_KEY"))).containsExactly(Map.entry("GIT_TOKEN", "s3cr3t"));
    }

    @Test
    void aMissingStoreFileResolvesToNothing(@TempDir Path dir) {
        FileSecretStore store = new FileSecretStore(dir.resolve("does-not-exist.json"));

        assertThat(store.resolve(List.of("GIT_TOKEN"))).isEmpty();
    }

    @Test
    void theStoreFileIsWrittenWithOwnerOnlyPermissions(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("secrets.json");
        assumeTrue(Files.getFileAttributeView(dir, PosixFileAttributeView.class) != null,
                "POSIX permissions not supported on this filesystem");

        FileSecretStore store = new FileSecretStore(file);
        store.put("GIT_TOKEN", "s3cr3t");

        assertThat(Files.getPosixFilePermissions(file)).containsExactlyInAnyOrder(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
    }
}
