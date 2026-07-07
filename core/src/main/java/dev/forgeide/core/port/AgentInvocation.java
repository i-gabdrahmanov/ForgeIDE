package dev.forgeide.core.port;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * @param env whitelisted per {@code env_scope} (SR-5); never contains SoT paths (SR-1)
 */
public record AgentInvocation(Path workingDir, String prompt, Duration timeout,
                               long tokenBudget, Map<String, String> env) {

    public AgentInvocation {
        Objects.requireNonNull(workingDir, "workingDir");
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(timeout, "timeout");
        env = Map.copyOf(env);
        if (tokenBudget <= 0) {
            throw new IllegalArgumentException("tokenBudget must be > 0");
        }
    }
}
