package dev.forgeide.runtime.process;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A single {@link ProcessRunner} invocation. Mirrors the shape of
 * {@code AgentInvocation}/{@code ScriptInvocation} (core) but is process-mechanics only:
 * {@link ProcessRunner} has no notion of stream-json or runtime-specific contracts (T09).
 *
 * @param env         only the explicitly supplied map is passed to the child; the IDE's own
 *                    environment is never inherited (SR-1/SR-5)
 * @param stdin       written then the stream is closed before the child is read; empty means
 *                    stdin is closed immediately with nothing written
 * @param maxOutputBytes combined stdout+stderr byte cap for the phase (SD §6.1 item 5);
 *                       see {@link ProcessRunner#DEFAULT_MAX_OUTPUT_BYTES}
 * @param stdoutLog   raw stdout lines are appended here as they are drained from the pipe
 * @param stderrLog   raw stderr lines are appended here as they are drained from the pipe
 */
public record ProcessSpec(Path workingDir, List<String> command, Map<String, String> env,
                           Optional<String> stdin, Duration timeout, long maxOutputBytes,
                           Path stdoutLog, Path stderrLog) {

    public ProcessSpec {
        Objects.requireNonNull(workingDir, "workingDir");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(stdin, "stdin");
        Objects.requireNonNull(stdoutLog, "stdoutLog");
        Objects.requireNonNull(stderrLog, "stderrLog");
        command = List.copyOf(command);
        env = Map.copyOf(env);
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        if (maxOutputBytes <= 0) {
            throw new IllegalArgumentException("maxOutputBytes must be > 0");
        }
    }
}
