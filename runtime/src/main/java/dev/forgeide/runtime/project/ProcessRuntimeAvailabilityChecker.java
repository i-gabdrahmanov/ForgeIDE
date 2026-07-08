package dev.forgeide.runtime.project;

import dev.forgeide.core.port.RuntimeAvailabilityChecker;
import dev.forgeide.core.project.RuntimeAvailability;
import dev.forgeide.core.project.RuntimeBinding;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Probes a runtime binary with {@code --version} (SDD FR-1.2). This is a bare availability
 * check, not the hardened process runner (pump threads, budgets, kill groups) built in T08 —
 * output is expected to be a short version string, read only after the process exits.
 */
public final class ProcessRuntimeAvailabilityChecker implements RuntimeAvailabilityChecker {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private final Duration timeout;

    public ProcessRuntimeAvailabilityChecker() {
        this(DEFAULT_TIMEOUT);
    }

    public ProcessRuntimeAvailabilityChecker(Duration timeout) {
        this.timeout = timeout;
    }

    @Override
    public RuntimeAvailability check(RuntimeBinding runtime) {
        Path binary = runtime.binaryPath();
        if (!Files.isExecutable(binary)) {
            return RuntimeAvailability.unavailable("binary not found or not executable: " + binary);
        }

        Process process;
        try {
            process = new ProcessBuilder(binary.toString(), "--version")
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            return RuntimeAvailability.unavailable("cannot run '" + binary + " --version': " + e.getMessage());
        }

        try {
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return RuntimeAvailability.unavailable(
                        "'" + binary + " --version' did not finish within " + timeout);
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (process.exitValue() != 0) {
                return RuntimeAvailability.unavailable("'" + binary + " --version' exited " + process.exitValue()
                        + (output.isBlank() ? "" : ": " + output));
            }
            return RuntimeAvailability.available(output.isBlank() ? "(no output)" : output);
        } catch (IOException e) {
            return RuntimeAvailability.unavailable("cannot read output of '" + binary + " --version': " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return RuntimeAvailability.unavailable("interrupted while checking '" + binary + "'");
        }
    }
}
