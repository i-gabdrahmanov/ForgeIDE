package dev.forgeide.ui.canvas;

import dev.forgeide.core.pipeline.validation.PipelineError;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TileErrorsTest {

    @Test
    void groupsByStepIdAndKeepsPipelineLevelSeparate() {
        List<PipelineError> errors = List.of(
                PipelineError.atStep("a", "depends_on", "unknown step 'ghost'"),
                PipelineError.atStep("a", "prompt", "missing required field 'prompt'"),
                PipelineError.atStep("b", "depends_on", "step is part of a dependency cycle"),
                PipelineError.atPipeline("steps", "steps must be a non-empty list"));

        Map<String, List<PipelineError>> byStep = TileErrors.byStep(errors);

        assertThat(byStep).containsOnlyKeys("a", "b");
        assertThat(byStep.get("a")).hasSize(2);
        assertThat(byStep.get("b")).hasSize(1);
        assertThat(TileErrors.pipelineLevel(errors)).hasSize(1)
                .allSatisfy(e -> assertThat(e.field()).isEqualTo("steps"));
    }

    @Test
    void badgeTextJoinsFieldAndMessagePerError() {
        List<PipelineError> stepErrors = List.of(
                PipelineError.atStep("a", "depends_on", "unknown step 'ghost'"),
                PipelineError.atStep("a", "prompt", "missing required field 'prompt'"));

        assertThat(TileErrors.badgeText(stepErrors)).isEqualTo(
                "depends_on: unknown step 'ghost'\nprompt: missing required field 'prompt'");
    }

    @Test
    void emptyErrorsProduceEmptyGroupingAndText() {
        assertThat(TileErrors.byStep(List.of())).isEmpty();
        assertThat(TileErrors.pipelineLevel(List.of())).isEmpty();
        assertThat(TileErrors.badgeText(List.of())).isEmpty();
    }
}
