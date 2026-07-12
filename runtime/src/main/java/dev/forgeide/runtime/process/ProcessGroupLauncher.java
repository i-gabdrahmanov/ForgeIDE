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
 * Launches a child in its own POSIX process group and kills that whole group on demand
 * (SDD SR-9).
 *
 * <p>Two mechanisms, picked at run time (T39): {@code setsid} where it exists (every Linux —
 * util-linux; {@code /bin/sh} there is dash, whose {@code set -m} silently turns itself off
 * without a controlling tty, leaving the child in the wrapper's own group), else job control
 * — a command backgrounded under {@code set -m} is forked into a new process group whose id
 * equals the child's own pid (macOS: no {@code setsid} binary, but {@code /bin/sh} is bash,
 * where this works tty or not). The wrapper writes that pid to a temp file (the only way to
 * learn it from outside — {@code ProcessBuilder} only ever hands back the wrapper shell's own
 * pid, not the backgrounded child's) then waits on it, so its exit code mirrors the child's.
 *
 * <p>Stdin: POSIX gives an asynchronous ({@code &}) command {@code /dev/null} as stdin unless
 * explicitly redirected, and dash enforces exactly that (bash under {@code set -m} does not —
 * which is why this only ever bit on Linux, silently eating agent prompts and hook payloads).
 * Hence the fd-3 dance: {@code exec 3<&0} first, then {@code <&3 3<&-} on the child — a plain
 * self-dup {@code <&0} is NOT enough for dash.
 */
final class ProcessGroupLauncher {

    private static final String WRAPPER_SCRIPT = "exec 3<&0; "
            + "if command -v setsid >/dev/null 2>&1; then setsid \"$@\" <&3 3<&- & "
            + "else set -m; \"$@\" <&3 3<&- & fi; "
            + "printf '%s' \"$!\" > \"$0\"; wait \"$!\"";
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
