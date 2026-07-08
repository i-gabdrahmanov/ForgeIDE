package dev.forgeide.runtime.process;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LineClassifierTest {

    @Test
    void classifiesValidJsonObjectsAsJson() {
        ParsedLine line = LineClassifier.classify("{\"type\":\"result\",\"ok\":true}");

        assertThat(line).isInstanceOfSatisfying(ParsedLine.Json.class,
                json -> assertThat(json.node().get("ok").asBoolean()).isTrue());
    }

    @Test
    void classifiesGarbageAndBlankLinesAsRaw() {
        assertThat(LineClassifier.classify("not json at all")).isEqualTo(new ParsedLine.Raw("not json at all"));
        assertThat(LineClassifier.classify("")).isEqualTo(new ParsedLine.Raw(""));
    }
}
