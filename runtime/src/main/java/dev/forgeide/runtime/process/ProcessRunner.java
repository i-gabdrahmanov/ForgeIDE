package dev.forgeide.runtime.process;

import dev.forgeide.core.port.ProcessSweepPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Hardened process launcher for agent/script phases (SD §6.1, SDD SR-9/NFR-1). Owns exactly
 * three concerns: getting stdio in and out without deadlocking, enforcing wall-clock/output
 * caps by killing the whole process group, and sweeping orphans a killed phase left behind.
 * Everything about what the output <em>means</em> (stream-json shapes, artifact contracts,
 * success criteria) is the caller's job (T09) — see {@link ParsedLine}/{@link ProcessOutcome}.
 */
public final class ProcessRunner implements ProcessSweepPort {

    /** Default per-phase combined stdout+stderr byte cap (SD §6.1 item 5). */
    public static final long DEFAULT_MAX_OUTPUT_BYTES = 512L * 1024 * 1024;

    private static final Logger log = LoggerFactory.getLogger(ProcessRunner.class);

    /**
     * Runs {@code spec} to completion: writes/closes stdin, drains stdout/stderr on their own
     * virtual threads while tee-ing raw lines to disk, and kills the whole process group if the
     * wall-clock timeout or output cap is exceeded. Blocks until the child has exited and both
     * pump threads have drained their pipe (SD §6.1 item 4) — never returns while output is
     * still being produced.
     *
     * @param onStdoutLine invoked once per stdout line, in order, after it has been durably
     *                     appended to {@link ProcessSpec#stdoutLog()}; throwing {@link
     *                     ProcessKillSignal} kills the process group immediately, the same as
     *                     hitting the timeout or output cap
     * @param onStderrLine invoked once per stderr line, in order, after it has been durably
     *                     appended to {@link ProcessSpec#stderrLog()}; also honors {@link
     *                     ProcessKillSignal}
     */
    public ProcessOutcome run(ProcessSpec spec, Consumer<ParsedLine> onStdoutLine, Consumer<String> onStderrLine) {
        Instant start = Instant.now();
        ProcessGroupLauncher.Launched launched;
        try {
            launched = ProcessGroupLauncher.start(spec.workingDir(), spec.command(), spec.env());
        } catch (IOException e) {
            throw new ProcessLaunchException("cannot start process: " + spec.command(), e);
        }

        Process process = launched.process();
        AtomicLong outputBytes = new AtomicLong();
        AtomicBoolean capExceeded = new AtomicBoolean();
        AtomicBoolean timedOut = new AtomicBoolean();
        AtomicBoolean killed = new AtomicBoolean();
        Runnable kill = () -> {
            if (killed.compareAndSet(false, true)) {
                ProcessGroupLauncher.killGroup(launched.pgid());
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        };

        try (ExecutorService pumps = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> stdinFuture = pumps.submit(() -> writeStdin(process, spec.stdin()));
            Future<?> stdoutFuture = pumps.submit(() -> pumpStdout(process, spec, onStdoutLine,
                    outputBytes, capExceeded, kill));
            Future<?> stderrFuture = pumps.submit(() -> pumpStderr(process, spec, onStderrLine,
                    outputBytes, capExceeded, kill));

            boolean finishedInTime = awaitWithinTimeout(process, spec.timeout());
            if (!finishedInTime) {
                timedOut.set(true);
                kill.run();
            }
            awaitExit(process);

            joinQuietly(stdinFuture);
            joinQuietly(stdoutFuture);
            joinQuietly(stderrFuture);
        }

        Duration wallClock = Duration.between(start, Instant.now());
        return new ProcessOutcome(process.exitValue(), timedOut.get(), capExceeded.get(),
                wallClock, OptionalLong.of(launched.pgid()));
    }

    /**
     * Sweeps processes whose cwd is under {@code projectDir} left running after a phase's
     * process group was killed (SDD SR-9: escapees that {@code setsid}'d/double-forked out of
     * the tracked group). Returns the pids it force-killed, for the caller to log as an
     * incident.
     */
    @Override
    public List<Long> sweepOrphans(Path projectDir) {
        Path normalizedProject = realOrNormalized(projectDir);
        long selfPid = ProcessHandle.current().pid();
        return CwdIndex.resolveAll().entrySet().stream()
                .filter(e -> e.getKey() != selfPid)
                .filter(e -> realOrNormalized(e.getValue()).startsWith(normalizedProject))
                .filter(e -> ProcessHandle.of(e.getKey()).map(ProcessHandle::destroyForcibly).orElse(false))
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * Resolves symlinks (e.g. macOS {@code /tmp} -> {@code /private/tmp}) before comparing a
     * candidate's cwd against the project directory: the kernel hands back canonical paths for
     * a process's cwd, so comparing against a non-canonical path would silently never match.
     */
    private static Path realOrNormalized(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException notResolvable) {
            return path.toAbsolutePath().normalize();
        }
    }

    private static void writeStdin(Process process, java.util.Optional<String> stdin) {
        try (OutputStream out = process.getOutputStream()) {
            if (stdin.isPresent()) {
                out.write(stdin.get().getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            // The child may have exited/closed its stdin already (e.g. it never reads a
            // prompt); that is not a launch failure, so this is intentionally swallowed.
        }
    }

    private void pumpStdout(Process process, ProcessSpec spec, Consumer<ParsedLine> onLine,
                             AtomicLong outputBytes, AtomicBoolean capExceeded, Runnable kill) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter rawLog = Files.newBufferedWriter(spec.stdoutLog(), StandardCharsets.UTF_8,
                     StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            String line;
            while ((line = reader.readLine()) != null) {
                rawLog.write(line);
                rawLog.newLine();
                rawLog.flush();
                if (overCap(outputBytes, capExceeded, line, spec.maxOutputBytes())) {
                    kill.run();
                    break;
                }
                try {
                    onLine.accept(parseLine(line));
                } catch (ProcessKillSignal killSignal) {
                    kill.run();
                    break;
                }
            }
        } catch (IOException e) {
            // A forced kill (timeout/output-cap/external) races with this thread's blocking
            // read and surfaces as a "stream closed" IOException here; that is an expected
            // consequence of killing, not a pump failure the caller needs to see.
            log.debug("stdout pump for {} ended: {}", spec.command(), e.toString());
        }
    }

    private void pumpStderr(Process process, ProcessSpec spec, Consumer<String> onLine,
                             AtomicLong outputBytes, AtomicBoolean capExceeded, Runnable kill) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8));
             BufferedWriter rawLog = Files.newBufferedWriter(spec.stderrLog(), StandardCharsets.UTF_8,
                     StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            String line;
            while ((line = reader.readLine()) != null) {
                rawLog.write(line);
                rawLog.newLine();
                rawLog.flush();
                if (overCap(outputBytes, capExceeded, line, spec.maxOutputBytes())) {
                    kill.run();
                    break;
                }
                try {
                    onLine.accept(line);
                } catch (ProcessKillSignal killSignal) {
                    kill.run();
                    break;
                }
            }
        } catch (IOException e) {
            log.debug("stderr pump for {} ended: {}", spec.command(), e.toString());
        }
    }

    private static boolean overCap(AtomicLong outputBytes, AtomicBoolean capExceeded, String line, long cap) {
        long total = outputBytes.addAndGet(line.length() + 1L);
        return total > cap && capExceeded.compareAndSet(false, true);
    }

    private ParsedLine parseLine(String line) {
        return LineClassifier.classify(line);
    }

    private static boolean awaitWithinTimeout(Process process, Duration timeout) {
        try {
            return process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return !process.isAlive();
        }
    }

    private static void awaitExit(Process process) {
        try {
            process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void joinQuietly(Future<?> future) {
        try {
            future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
