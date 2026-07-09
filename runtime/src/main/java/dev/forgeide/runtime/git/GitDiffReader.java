package dev.forgeide.runtime.git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Best-effort {@code git diff HEAD} for the gate dialog (SDD FR-5.2: "real data from disk, not
 * the model's own word"). Never throws — a non-repo working dir, a missing {@code git} binary,
 * or a hung process all just yield a human-readable placeholder string rather than failing the
 * dialog; a watchdog thread force-kills the process past {@code timeout} so a large diff can
 * never deadlock on a full pipe while nobody is draining it.
 */
public final class GitDiffReader {

    private GitDiffReader() {
    }

    public static String read(Path repoRoot, Duration timeout) {
        Process process;
        try {
            process = new ProcessBuilder("git", "diff", "HEAD")
                    .directory(repoRoot.toFile())
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            return "(git diff unavailable: " + e.getMessage() + ")";
        }

        Thread watchdog = Thread.ofVirtual().start(() -> {
            try {
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        try {
            // Read before waiting: git diff output can exceed the pipe buffer, so draining it
            // is what lets the process finish — waiting first would risk a deadlock.
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor();
            if (process.exitValue() != 0) {
                return "(git diff unavailable — exit " + process.exitValue() + ": " + output.strip() + ")";
            }
            return output.isBlank() ? "(no changes)" : output;
        } catch (IOException e) {
            return "(git diff unavailable: " + e.getMessage() + ")";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "(git diff interrupted)";
        } finally {
            watchdog.interrupt();
            process.destroyForcibly();
        }
    }
}
