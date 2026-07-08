package dev.forgeide.runtime.process;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Tolerant per-line JSON-vs-raw classification (SD §6.1 item 2), extracted out of {@link
 * ProcessRunner} so the same tolerant parse used live on a running phase's stdout can be reused
 * by the UI's log tailer (T10) reading the same {@code stdout.jsonl} file after the fact.
 */
public final class LineClassifier {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LineClassifier() {
    }

    public static ParsedLine classify(String line) {
        if (line.isBlank()) {
            return new ParsedLine.Raw(line);
        }
        try {
            JsonNode node = MAPPER.readTree(line);
            if (node == null) {
                return new ParsedLine.Raw(line);
            }
            return new ParsedLine.Json(node);
        } catch (JsonProcessingException notJson) {
            return new ParsedLine.Raw(line);
        }
    }
}
