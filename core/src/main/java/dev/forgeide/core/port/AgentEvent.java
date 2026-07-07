package dev.forgeide.core.port;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Parsed line from an agent phase's stdout stream-json (SD §6.1). {@code RawLine}
 * is the tolerant fallback for non-JSON output (runtimes print warnings to stdout).
 */
public sealed interface AgentEvent {

    record ToolUse(String name, JsonNode input) implements AgentEvent {
    }

    record Usage(TokenUsage usage) implements AgentEvent {
    }

    record Result(JsonNode finalJson) implements AgentEvent {
    }

    record RawLine(String line) implements AgentEvent {
    }
}
