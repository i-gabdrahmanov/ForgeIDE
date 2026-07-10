package dev.forgeide.core.pipeline.edit;

import dev.forgeide.core.pipeline.AgentStep;
import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.StepDefinition;
import dev.forgeide.core.pipeline.validation.PipelineError;
import dev.forgeide.core.pipeline.validation.PipelineValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T23 acceptance: "созданная с нуля agent-плитка валидна". Reproduces exactly what a canvas
 * drop does — {@link StepDefaults#create} for the default, then seed the prompt file it points
 * at with {@link AgentPromptScaffold} (the two steps {@code ConstructorCanvasView}'s
 * {@code addStepAt} and {@code PipelineConstructorView#seedAgentPrompt} perform together) — and
 * checks the result against the same {@link PipelineValidator} the canvas's live validation and
 * "запуск прогона заблокирован до зелёной валидации" gate (FR-2.6) both run.
 */
class NewAgentTileEndToEndTest {

    @Test
    void freshAgentTileIsValidOnceItsScaffoldedPromptIsOnDisk(@TempDir Path projectRoot) throws IOException {
        StepDefinition step = StepDefaults.create(StepKind.AGENT, "agent-1");
        assertThat(step).isInstanceOf(AgentStep.class);
        AgentStep agent = (AgentStep) step;

        Path promptFile = projectRoot.resolve(agent.promptTemplate());
        Files.createDirectories(promptFile.getParent());
        Files.writeString(promptFile, AgentPromptScaffold.render(agent.id()));

        PipelineDefinition pipeline = new PipelineDefinition("pipeline", 1, List.of(step));
        List<PipelineError> errors = new PipelineValidator().validate(pipeline, PipelineValidator.Options.withRoot(projectRoot));

        assertThat(errors).isEmpty();
    }

    @Test
    void freshAgentTileIsInvalidBeforeThePromptIsSeeded(@TempDir Path projectRoot) {
        StepDefinition step = StepDefaults.create(StepKind.AGENT, "agent-1");
        PipelineDefinition pipeline = new PipelineDefinition("pipeline", 1, List.of(step));

        List<PipelineError> errors = new PipelineValidator().validate(pipeline, PipelineValidator.Options.withRoot(projectRoot));

        assertThat(errors).isNotEmpty(); // "prompt file not found" — badges the tile, as FR-2.6 expects
    }
}
