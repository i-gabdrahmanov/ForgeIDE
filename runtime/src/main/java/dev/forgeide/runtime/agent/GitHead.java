package dev.forgeide.runtime.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Best-effort {@code git rev-parse HEAD} for {@code meta.json}'s {@code head_before}/{@code
 * head_after} (SDD §5.4 — scope-diff/incident-investigation support). Never throws: a
 * non-repo working dir, a missing {@code git} binary, or a slow/hanging call all just yield
 * {@link Optional#empty()} rather than failing the phase.
 */
final class GitHead {

    private GitHead() {
    }

    static Optional<String> read(Path workingDir) {
        Process process;
        try {
            process = new ProcessBuilder("git", "rev-parse", "HEAD")
                    .directory(workingDir.toFile())
                    .redirectErrorStream(false)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException e) {
            return Optional.empty();
        }
        try {
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return Optional.empty();
            }
            if (process.exitValue() != 0) {
                return Optional.empty();
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            return output.isBlank() ? Optional.empty() : Optional.of(output);
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            process.destroyForcibly();
        }
    }
}
