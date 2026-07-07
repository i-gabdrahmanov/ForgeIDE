package dev.forgeide.core.port;

public record TokenUsage(long inputTokens, long outputTokens) {

    public TokenUsage {
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("token counts must be >= 0");
        }
    }

    public long total() {
        return inputTokens + outputTokens;
    }
}
