package dev.forgeide.core.port;

import dev.forgeide.core.project.RuntimeBinding;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * @param maxOutputBytes combined stdout+stderr byte cap for the phase ({@code
 *                       budget.output_mb}), forwarded to the process launcher
 * @param logDir         directory the runtime writes {@code stdout.jsonl}/{@code stderr.log}/
 *                       {@code meta.json} into (SD §6.2)
 * @param runtime        which CLI to invoke (name selects the adapter; binaryPath/flags build
 *                       its argv) — resolved from the step's {@code runtime:} ref (SDD FR-4)
 * @param env            whitelisted per {@code env_scope} (SR-5); never contains SoT paths (SR-1)
 */
public record AgentInvocation(Path workingDir, String prompt, Duration timeout, long tokenBudget,
                               long maxOutputBytes, Path logDir, RuntimeBinding runtime,
                               Map<String, String> env) {

    public AgentInvocation {
        Objects.requireNonNull(workingDir, "workingDir");
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(logDir, "logDir");
        Objects.requireNonNull(runtime, "runtime");
        env = Map.copyOf(env);
        if (tokenBudget <= 0) {
            throw new IllegalArgumentException("tokenBudget must be > 0");
        }
        if (maxOutputBytes <= 0) {
            throw new IllegalArgumentException("maxOutputBytes must be > 0");
        }
    }
}
