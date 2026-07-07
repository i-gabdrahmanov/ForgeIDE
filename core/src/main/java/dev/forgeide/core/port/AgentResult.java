package dev.forgeide.core.port;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * @param finalJson empty means no {@code result} event was seen -> caller fails the
 *                  step {@code FAILED(stream)} (SDD FR-4.2)
 */
public record AgentResult(int exitCode, Optional<JsonNode> finalJson, TokenUsage usage, Path rawLog) {

    public AgentResult {
        Objects.requireNonNull(finalJson, "finalJson");
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(rawLog, "rawLog");
    }
}
