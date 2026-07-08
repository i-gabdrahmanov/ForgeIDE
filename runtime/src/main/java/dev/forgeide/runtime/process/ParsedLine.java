package dev.forgeide.runtime.process;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Tolerant per-line parse of a pumped stdout line (SD §6.1 item 2). {@link ProcessRunner} only
 * knows whether a line is JSON at all — mapping a {@link Json} node into a runtime-specific
 * shape ({@code tool_use}/{@code usage}/{@code result}, ...) is out of scope here (T09).
 */
public sealed interface ParsedLine {

    record Json(JsonNode node) implements ParsedLine {
    }

    record Raw(String line) implements ParsedLine {
    }
}
