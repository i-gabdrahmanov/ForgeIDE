package dev.forgeide.runtime.process;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Launches a child in its own POSIX process group without any external {@code setsid} binary
 * (absent by default on macOS) and kills that whole group on demand (SDD SR-9).
 *
 * <p>The trick is plain job control, available in every POSIX shell: a command backgrounded
 * under {@code set -m} is forked into a new process group whose id equals the child's own pid
 * — this is exactly what {@code setsid}-less tools (incl. Python's {@code start_new_session})
 * rely on under the hood. The wrapper writes that pid to a temp file (the only way to learn it
 * from outside — {@code ProcessBuilder} only ever hands back the wrapper shell's own pid, not
 * the backgrounded child's) then waits on it, so its own exit code mirrors the child's.
 */
final class ProcessGroupLauncher {

    private static final String WRAPPER_SCRIPT = "set -m; \"$@\" & printf '%s' \"$!\" > \"$0\"; wait \"$!\"";
    private static final Duration PGID_DISCOVERY_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration PGID_POLL_INTERVAL = Duration.ofMillis(2);

    private ProcessGroupLauncher() {
    }

    static boolean isSupportedPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("linux") || os.contains("mac") || os.contains("darwin");
    }

    record Launched(Process process, long pgid) {
    }

    static Launched start(Path workingDir, List<String> command, Map<String, String> env) throws IOException {
        if (!isSupportedPlatform()) {
            throw new ProcessLaunchException(
                    "ProcessRunner requires POSIX process-group semantics (macOS/Linux); "
                            + "Windows is not yet supported (NFR-5)");
        }
        Path pgidFile = Files.createTempFile("forgeide-pgid-", ".txt");
        try {
            List<String> wrapped = new ArrayList<>();
            wrapped.add("/bin/sh");
            wrapped.add("-c");
            wrapped.add(WRAPPER_SCRIPT);
            wrapped.add(pgidFile.toString());
            wrapped.addAll(command);

            ProcessBuilder builder = new ProcessBuilder(wrapped);
            builder.directory(workingDir.toFile());
            builder.environment().clear();
            builder.environment().putAll(env);
            builder.redirectErrorStream(false);

            Process process = builder.start();
            long pgid = awaitPgid(pgidFile, process);
            return new Launched(process, pgid);
        } finally {
            Files.deleteIfExists(pgidFile);
        }
    }

    /** Sends {@code SIGKILL} to the whole process group (negative pid, POSIX kill(2) semantics). */
    static void killGroup(long pgid) {
        try {
            Process killer = new ProcessBuilder("kill", "-9", "-" + pgid)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            killer.waitFor(2, TimeUnit.SECONDS);
        } catch (IOException e) {
            // Best-effort: the caller also destroys descendants/the wrapper handle directly.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long awaitPgid(Path pgidFile, Process process) throws IOException {
        Instant deadline = Instant.now().plus(PGID_DISCOVERY_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            String content = Files.readString(pgidFile, StandardCharsets.UTF_8);
            if (!content.isBlank()) {
                try {
                    return Long.parseLong(content.trim());
                } catch (NumberFormatException ignored) {
                    // torn read mid-write; keep polling
                }
            }
            if (!process.isAlive()) {
                // Exited before publishing its pgid: re-check once more, content may have
                // landed on disk right before exit.
                content = Files.readString(pgidFile, StandardCharsets.UTF_8);
                if (!content.isBlank()) {
                    try {
                        return Long.parseLong(content.trim());
                    } catch (NumberFormatException ignored) {
                        break;
                    }
                }
                break;
            }
            try {
                Thread.sleep(PGID_POLL_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ProcessLaunchException("interrupted while discovering process group", e);
            }
        }
        throw new ProcessLaunchException("could not discover process group id within "
                + PGID_DISCOVERY_TIMEOUT);
    }
}
