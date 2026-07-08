package dev.forgeide.runtime.state;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Stable per-repository directory name for {@link FileStateStore#defaultRoot} (SD §4:
 * {@code ~/.forgeide/state/<project-hash>/<pipeline>/}) — sha256-hex of the repository's
 * canonicalized path, so the same project always lands in the same state directory regardless
 * of which symlink/relative path it was opened through.
 */
public final class ProjectHash {

    private ProjectHash() {
    }

    public static String of(Path repositoryPath) {
        return sha256Hex(realOrNormalized(repositoryPath).toString());
    }

    private static Path realOrNormalized(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException notResolvable) {
            return path.toAbsolutePath().normalize();
        }
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
