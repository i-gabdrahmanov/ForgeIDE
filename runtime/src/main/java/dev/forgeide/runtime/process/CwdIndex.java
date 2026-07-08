package dev.forgeide.runtime.process;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Resolves the working directory of running processes, for the orphan sweep (SDD SR-9).
 * {@link ProcessHandle} has no cross-platform notion of "cwd", so this is necessarily
 * OS-specific: Linux exposes it as a symlink per pid under {@code /proc}; macOS has no
 * {@code /proc}, so a single {@code lsof} call resolves all of them at once (one process-wide
 * lsof invocation, not one per candidate pid — keeps the post-phase sweep within NFR-4's
 * overhead budget).
 */
final class CwdIndex {

    private CwdIndex() {
    }

    static Map<Long, Path> resolveAll() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("linux")) {
            return resolveViaProc();
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return resolveViaLsof();
        }
        return Map.of();
    }

    private static Map<Long, Path> resolveViaProc() {
        Map<Long, Path> result = new HashMap<>();
        Path procRoot = Path.of("/proc");
        if (!Files.isDirectory(procRoot)) {
            return result;
        }
        try (var entries = Files.newDirectoryStream(procRoot)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                long pid;
                try {
                    pid = Long.parseLong(name);
                } catch (NumberFormatException notAPid) {
                    continue;
                }
                Path cwdLink = entry.resolve("cwd");
                try {
                    if (Files.exists(cwdLink, LinkOption.NOFOLLOW_LINKS)) {
                        result.put(pid, Files.readSymbolicLink(cwdLink));
                    }
                } catch (IOException unreadable) {
                    // Foreign-uid process or it exited mid-scan; not ours to sweep either way.
                }
            }
        } catch (IOException e) {
            return result;
        }
        return result;
    }

    private static Map<Long, Path> resolveViaLsof() {
        Map<Long, Path> result = new HashMap<>();
        Process lsof;
        try {
            lsof = new ProcessBuilder("lsof", "-d", "cwd", "-Fpn")
                    .redirectErrorStream(false)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException e) {
            return result;
        }
        Long currentPid = null;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(lsof.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                char field = line.charAt(0);
                String value = line.substring(1);
                if (field == 'p') {
                    try {
                        currentPid = Long.parseLong(value);
                    } catch (NumberFormatException ignored) {
                        currentPid = null;
                    }
                } else if (field == 'n' && currentPid != null) {
                    result.put(currentPid, Path.of(value));
                }
            }
        } catch (IOException ignored) {
            // Partial output is still useful; fall through with whatever was parsed so far.
        } finally {
            try {
                lsof.waitFor(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            lsof.destroyForcibly();
        }
        return result;
    }
}
