package dev.forgeide.ui.canvas;

import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.yaml.PipelineTemplates;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StepTileStylesTest {

    private final PipelineDefinition forgelite = PipelineTemplates.forgelite();

    @Test
    void agentStepGetsItsOwnCssClassTypeLabelAndPromptSummary() {
        var step = forgelite.step("lite-ground");

        assertThat(StepTileStyles.cssClass(step)).isEqualTo("tile-agent");
        assertThat(StepTileStyles.typeLabel(step)).isEqualTo("Agent");
        assertThat(StepTileStyles.summary(step)).isEqualTo("gigacode · prompts/lite-ground.md");
    }

    @Test
    void gateStepShowsItsQuestion() {
        var step = forgelite.step("gate-design");

        assertThat(StepTileStyles.cssClass(step)).isEqualTo("tile-gate");
        assertThat(StepTileStyles.summary(step)).isEqualTo("Утвердить tech-design?");
    }

    @Test
    void judgeStepSummarisesTargetAndCheckKind() {
        var step = forgelite.step("judge-red");

        assertThat(StepTileStyles.cssClass(step)).isEqualTo("tile-judge");
        assertThat(StepTileStyles.summary(step)).isEqualTo("target: lite-red · check");
    }

    @Test
    void outwardStepListsItsActions() {
        var step = forgelite.step("deliver");

        assertThat(StepTileStyles.cssClass(step)).isEqualTo("tile-outward");
        assertThat(StepTileStyles.summary(step)).isEqualTo("GIT_PUSH, CREATE_PR");
    }

    @Test
    void everyStepTypeInForgeliteHasADistinctCssClass() {
        assertThat(forgelite.steps().stream().map(StepTileStyles::cssClass).distinct().toList())
                .containsExactlyInAnyOrder("tile-agent", "tile-gate", "tile-judge", "tile-outward");
    }
}
