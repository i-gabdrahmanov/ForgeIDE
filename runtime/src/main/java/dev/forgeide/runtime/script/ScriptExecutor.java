package dev.forgeide.runtime.script;

import dev.forgeide.core.port.ScriptInvocation;
import dev.forgeide.core.port.ScriptResult;
import dev.forgeide.core.port.ScriptRunnerException;
import dev.forgeide.core.port.ScriptRunnerPort;
import dev.forgeide.runtime.process.ProcessLaunchException;
import dev.forgeide.runtime.process.ProcessOutcome;
import dev.forgeide.runtime.process.ProcessRunner;
import dev.forgeide.runtime.process.ProcessSpec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;

/**
 * {@code script}/judge-recheck steps (SD §6, FR-6.3): runs {@link ScriptInvocation#command()}
 * via {@link ProcessRunner} and hands back its exit code plus captured stdout/stderr.
 * {@code script} steps have no token/output budget of their own (unlike agent phases), so the
 * process-wide default output cap applies and stdio never needs a durable, reproducible home —
 * it is captured to a throwaway temp dir, read back into strings for {@link ScriptResult}, then
 * discarded.
 */
public final class ScriptExecutor implements ScriptRunnerPort {

    private final ProcessRunner processRunner;

    public ScriptExecutor() {
        this(new ProcessRunner());
    }

    public ScriptExecutor(ProcessRunner processRunner) {
        this.processRunner = processRunner;
    }

    @Override
    public ScriptResult run(ScriptInvocation invocation) throws ScriptRunnerException {
        Path tmpDir;
        try {
            tmpDir = Files.createTempDirectory("forgeide-script-");
        } catch (IOException e) {
            throw new ScriptRunnerException("cannot create temp dir for script stdio", e);
        }
        try {
            Path stdoutLog = tmpDir.resolve("stdout.log");
            Path stderrLog = tmpDir.resolve("stderr.log");
            ProcessSpec spec = new ProcessSpec(invocation.workingDir(), invocation.command(), invocation.env(),
                    Optional.empty(), invocation.timeout(), ProcessRunner.DEFAULT_MAX_OUTPUT_BYTES,
                    stdoutLog, stderrLog);

            ProcessOutcome outcome;
            try {
                outcome = processRunner.run(spec, line -> { }, line -> { });
            } catch (ProcessLaunchException e) {
                throw new ScriptRunnerException("cannot launch script: " + invocation.command(), e);
            }

            return new ScriptResult(outcome.exitCode(), readQuietly(stdoutLog), readQuietly(stderrLog));
        } finally {
            deleteRecursively(tmpDir);
        }
    }

    private static String readQuietly(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static void deleteRecursively(Path dir) {
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Best-effort cleanup of a throwaway temp dir; nothing downstream reads it.
                }
            });
        } catch (IOException ignored) {
            // Same as above.
        }
    }
}
