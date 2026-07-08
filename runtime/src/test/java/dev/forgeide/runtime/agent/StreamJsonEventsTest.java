package dev.forgeide.runtime.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.forgeide.core.port.AgentEvent;
import dev.forgeide.runtime.process.ParsedLine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StreamJsonEventsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void assistantToolUseBlockBecomesAToolUseEvent() throws Exception {
        JsonNode node = mapper.readTree("""
                {"type":"assistant","message":{"content":[
                  {"type":"text","text":"looking"},
                  {"type":"tool_use","name":"Bash","input":{"command":"ls"}}
                ]}}
                """);

        List<AgentEvent> events = StreamJsonEvents.parse(new ParsedLine.Json(node));

        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOfSatisfying(AgentEvent.ToolUse.class, toolUse -> {
            assertThat(toolUse.name()).isEqualTo("Bash");
            assertThat(toolUse.input().get("command").asText()).isEqualTo("ls");
        });
    }

    @Test
    void assistantMessageUsageBecomesAUsageEvent() throws Exception {
        JsonNode node = mapper.readTree("""
                {"type":"assistant","message":{"content":[],"usage":{"input_tokens":10,"output_tokens":5}}}
                """);

        List<AgentEvent> events = StreamJsonEvents.parse(new ParsedLine.Json(node));

        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOfSatisfying(AgentEvent.Usage.class, usage -> {
            assertThat(usage.usage().inputTokens()).isEqualTo(10);
            assertThat(usage.usage().outputTokens()).isEqualTo(5);
        });
    }

    @Test
    void resultEventWithJsonResultTextProducesUsageAndResultEvents() throws Exception {
        JsonNode node = mapper.readTree("""
                {"type":"result","usage":{"input_tokens":100,"output_tokens":40},
                 "result":"{\\"step_id\\":\\"work\\",\\"status\\":\\"done\\",\\"artifacts\\":[]}"}
                """);

        List<AgentEvent> events = StreamJsonEvents.parse(new ParsedLine.Json(node));

        assertThat(events).hasSize(2);
        assertThat(events).anySatisfy(e -> assertThat(e).isInstanceOfSatisfying(AgentEvent.Usage.class,
                u -> assertThat(u.usage().total()).isEqualTo(140)));
        assertThat(events).anySatisfy(e -> assertThat(e).isInstanceOfSatisfying(AgentEvent.Result.class,
                r -> assertThat(r.finalJson().get("step_id").asText()).isEqualTo("work")));
    }

    @Test
    void resultEventWithNonJsonResultTextProducesNoResultEvent() throws Exception {
        JsonNode node = mapper.readTree("""
                {"type":"result","usage":{"input_tokens":1,"output_tokens":1},
                 "result":"plain prose, not the contract JSON"}
                """);

        List<AgentEvent> events = StreamJsonEvents.parse(new ParsedLine.Json(node));

        assertThat(events).noneMatch(AgentEvent.Result.class::isInstance);
        assertThat(events).hasSize(1); // usage still surfaces
    }

    @Test
    void systemAndUserLinesProduceNoEvents() throws Exception {
        JsonNode system = mapper.readTree("{\"type\":\"system\",\"subtype\":\"init\"}");
        JsonNode user = mapper.readTree("{\"type\":\"user\",\"message\":{\"content\":[]}}");

        assertThat(StreamJsonEvents.parse(new ParsedLine.Json(system))).isEmpty();
        assertThat(StreamJsonEvents.parse(new ParsedLine.Json(user))).isEmpty();
    }

    @Test
    void garbageRawLineBecomesARawLineEvent() {
        List<AgentEvent> events = StreamJsonEvents.parse(new ParsedLine.Raw("not json at all"));

        assertThat(events).containsExactly(new AgentEvent.RawLine("not json at all"));
    }
}
