package dev.forgeide.core.pipeline;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipelineDefinitionTest {

    private static ScriptStep script(String id, List<String> dependsOn) {
        return new ScriptStep(id, dependsOn, List.of("echo", "ok"), Duration.ofMinutes(1));
    }

    @Test
    void rejectsDuplicateStepIds() {
        List<StepDefinition> steps = List.of(script("a", List.of()), script("a", List.of()));

        assertThatThrownBy(() -> new PipelineDefinition("pl", 1, steps))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a");
    }

    @Test
    void acceptsUniqueStepIdsAndResolvesById() {
        StepDefinition a = script("a", List.of());
        StepDefinition b = script("b", List.of("a"));

        PipelineDefinition definition = new PipelineDefinition("pl", 1, List.of(a, b));

        assertThat(definition.steps()).containsExactly(a, b);
        assertThat(definition.step("b")).isSameAs(b);
    }

    @Test
    void unknownStepIdThrows() {
        PipelineDefinition definition = new PipelineDefinition("pl", 1, List.of(script("a", List.of())));

        assertThatThrownBy(() -> definition.step("missing")).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void stepsListIsImmutable() {
        PipelineDefinition definition = new PipelineDefinition("pl", 1, List.of(script("a", List.of())));

        assertThatThrownBy(() -> definition.steps().add(script("b", List.of())))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
