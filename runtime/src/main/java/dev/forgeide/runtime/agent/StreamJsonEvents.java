package dev.forgeide.runtime.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import dev.forgeide.core.port.AgentEvent;
import dev.forgeide.core.port.TokenUsage;
import dev.forgeide.runtime.process.ParsedLine;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps a pumped stdout line to zero or more {@link AgentEvent}s (SD §6.1 item 2). Shared by
 * every runtime adapter: GigaCode is a rebrand of the same Claude-Code-style CLI, so both
 * emit the same stream-json shape — {@code assistant} messages carry {@code tool_use} content
 * blocks (and a running {@code usage} total for that turn), and the terminal {@code result}
 * event carries the session-wide {@code usage} plus the agent's own final answer as a string
 * in its {@code result} field. That final answer is, by prompt contract (SDD §5.2), itself a
 * JSON blob ({@code step_id}/{@code artifacts}/{@code pending_questions}/{@code summary}) — if
 * it fails to parse as JSON, no {@link AgentEvent.Result} is produced and the caller ends up
 * with an empty {@code finalJson}, i.e. {@code FAILED(stream)}.
 *
 * <p>Lines that match neither shape (system/init events, user/tool-result echoes) are not
 * surfaced as events — {@link dev.forgeide.runtime.process.ProcessRunner} already tees every
 * raw line to disk regardless.
 *
 * <p>Public (not just package-private) so the UI's log tailer (T10) can reuse the same mapping
 * for the "parsed events" tab when reading a finished or in-flight {@code stdout.jsonl} back
 * off disk, instead of re-implementing this shape-matching a second time.
 */
public final class StreamJsonEvents {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StreamJsonEvents() {
    }

    public static List<AgentEvent> parse(ParsedLine line) {
        if (line instanceof ParsedLine.Raw raw) {
            return List.of(new AgentEvent.RawLine(raw.line()));
        }
        JsonNode node = ((ParsedLine.Json) line).node();
        String type = textOrNull(node, "type");
        if ("assistant".equals(type)) {
            return assistantEvents(node);
        }
        if ("result".equals(type)) {
            return resultEvents(node);
        }
        return List.of();
    }

    private static List<AgentEvent> assistantEvents(JsonNode node) {
        JsonNode message = node.get("message");
        if (message == null) {
            return List.of();
        }
        List<AgentEvent> events = new ArrayList<>();
        JsonNode content = message.get("content");
        if (content != null && content.isArray()) {
            for (JsonNode block : content) {
                if ("tool_use".equals(textOrNull(block, "type"))) {
                    String name = textOrNull(block, "name");
                    JsonNode input = block.get("input");
                    events.add(new AgentEvent.ToolUse(name == null ? "" : name,
                            input == null ? NullNode.getInstance() : input));
                }
            }
        }
        JsonNode usage = message.get("usage");
        if (usage != null && usage.isObject()) {
            events.add(new AgentEvent.Usage(toTokenUsage(usage)));
        }
        return events;
    }

    private static List<AgentEvent> resultEvents(JsonNode node) {
        List<AgentEvent> events = new ArrayList<>();
        JsonNode usage = node.get("usage");
        if (usage != null && usage.isObject()) {
            events.add(new AgentEvent.Usage(toTokenUsage(usage)));
        }
        JsonNode resultText = node.get("result");
        if (resultText != null && resultText.isTextual()) {
            JsonNode parsed = tryParseJson(resultText.asText());
            if (parsed != null) {
                events.add(new AgentEvent.Result(parsed));
            }
        }
        return events;
    }

    private static JsonNode tryParseJson(String text) {
        try {
            JsonNode parsed = MAPPER.readTree(text);
            return parsed != null && (parsed.isObject() || parsed.isArray()) ? parsed : null;
        } catch (JsonProcessingException notJson) {
            return null;
        }
    }

    private static TokenUsage toTokenUsage(JsonNode usage) {
        return new TokenUsage(longOrZero(usage, "input_tokens"), longOrZero(usage, "output_tokens"));
    }

    private static long longOrZero(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isNumber() ? value.asLong() : 0L;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }
}
