package dev.forgeide.runtime.harness;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Scans a project's harness (SDD SR-8: {@code hooks/}, {@code skills/}, {@code
 * settings.hooks.json}, all under {@code <project>/.gigacode/}) into a hash-manifest — relative
 * path -> SHA-256 of that file's bytes — plus a single aggregate hash of the whole manifest, and
 * diffs two such manifests for the {@code STOPPED(harness-drift)} audit payload.
 */
final class HarnessManifest {

    private static final int MAX_DIFF_ENTRIES = 20;

    private HarnessManifest() {
    }

    /** Empty (not missing-directory-tolerant per subdir) if the project has no {@code .gigacode/}
     * at all yet — a project that has never had a harness deployed to it has nothing to hash. */
    static Map<String, String> scan(Path projectRoot) {
        Path root = HarnessLayout.harnessRoot(projectRoot);
        Map<String, String> manifest = new TreeMap<>();
        for (String subdir : HarnessLayout.SCRIPT_SUBDIRS) {
            Path dir = root.resolve(subdir);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(Files::isRegularFile)
                        .forEach(file -> manifest.put(relativize(root, file), sha256Hex(file)));
            } catch (IOException e) {
                throw new UncheckedIOException("failed to scan harness directory: " + dir, e);
            }
        }
        Path settings = root.resolve(HarnessLayout.SETTINGS_FILE);
        if (Files.isRegularFile(settings)) {
            manifest.put(HarnessLayout.SETTINGS_FILE, sha256Hex(settings));
        }
        return manifest;
    }

    /** SHA-256 of the manifest's {@code path=hash} lines, sorted (the map is already a
     * {@link TreeMap}) — content-addressed name for {@link HarnessLayout#cacheDir}. */
    static String aggregateHash(Map<String, String> manifest) {
        StringBuilder sb = new StringBuilder();
        manifest.forEach((path, hash) -> sb.append(path).append('=').append(hash).append('\n'));
        return sha256Hex(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Human-readable added/removed/modified summary between {@code baseline} and {@code
     * current}, capped so a wholesale re-deploy doesn't produce an unreadable wall of text. */
    static String diff(Map<String, String> baseline, Map<String, String> current) {
        Set<String> paths = new LinkedHashSet<>();
        paths.addAll(baseline.keySet());
        paths.addAll(current.keySet());
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (String path : paths) {
            String before = baseline.get(path);
            String after = current.get(path);
            if (java.util.Objects.equals(before, after)) {
                continue;
            }
            if (shown >= MAX_DIFF_ENTRIES) {
                sb.append("... and more\n");
                break;
            }
            if (before == null) {
                sb.append("+ added: ").append(path).append('\n');
            } else if (after == null) {
                sb.append("- removed: ").append(path).append('\n');
            } else {
                sb.append("~ modified: ").append(path).append('\n');
            }
            shown++;
        }
        return sb.toString();
    }

    private static String relativize(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static String sha256Hex(Path file) {
        try {
            return sha256Hex(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read harness file: " + file, e);
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
