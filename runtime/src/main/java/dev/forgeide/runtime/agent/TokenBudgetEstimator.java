package dev.forgeide.runtime.agent;

import dev.forgeide.core.port.TokenUsage;

import java.time.Duration;

/**
 * Fallback token-usage estimate for when a runtime's stream never emits a {@code usage}
 * object (SD §6: "оценка по байтам + wall-clock"). Uses the common ~4-chars-per-token
 * heuristic for the prompt (input) and captured stdout (output); the wall-clock elapsed
 * seconds is used as a floor on the output estimate so a process that produced no output at
 * all (e.g. hung, then killed by timeout) still counts for something against the budget
 * rather than reporting zero usage.
 */
final class TokenBudgetEstimator {

    private static final int CHARS_PER_TOKEN = 4;

    private TokenBudgetEstimator() {
    }

    static TokenUsage estimate(String prompt, long outputBytes, Duration wallClock) {
        long inputTokens = Math.max(0, prompt.length() / CHARS_PER_TOKEN);
        long outputTokens = Math.max(outputBytes / CHARS_PER_TOKEN, Math.max(0, wallClock.getSeconds()));
        return new TokenUsage(inputTokens, outputTokens);
    }
}
