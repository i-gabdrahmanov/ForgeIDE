package dev.forgeide.core.pipeline.edit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.forgeide.core.engine.PipelineEngine;
import dev.forgeide.core.engine.support.FixtureAgentRuntimePort;
import dev.forgeide.core.engine.support.FixtureScriptRunnerPort;
import dev.forgeide.core.engine.support.InMemoryStateStore;
import dev.forgeide.core.engine.support.TestProjects;
import dev.forgeide.core.pipeline.AgentStep;
import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.StepDefinition;
import dev.forgeide.core.pipeline.validation.PipelineError;
import dev.forgeide.core.pipeline.validation.PipelineValidator;
import dev.forgeide.core.port.AgentResult;
import dev.forgeide.core.port.TokenUsage;
import dev.forgeide.core.run.RunId;
import dev.forgeide.core.run.RunStatus;
import dev.forgeide.core.run.StepStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static dev.forgeide.core.engine.support.Await.until;
import static dev.forgeide.core.engine.support.Snapshots.statusOf;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * T23 acceptance: "созданная с нуля agent-плитка валидна". Reproduces exactly what a canvas
 * drop does — {@link StepDefaults#create} for the default, then seed the prompt file it points
 * at with {@link AgentPromptScaffold} (the two steps {@code ConstructorCanvasView}'s
 * {@code addStepAt} and {@code PipelineConstructorView#seedAgentPrompt} perform together) — and
 * checks the result against the same {@link PipelineValidator} the canvas's live validation and
 * "запуск прогона заблокирован до зелёной валидации" gate (FR-2.6) both run.
 *
 * <p>T31 extends this from "valid" to "actually runs": a fresh tile is not just accepted by the
 * validator, it also completes end-to-end on the fixture runtime once seeded, same as any other
 * agent step in the engine's own transition tests.
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
    void freshAgentTileRunsEndToEndOnTheFixtureRuntimeOnceItsScaffoldedPromptIsOnDisk(@TempDir Path projectRoot)
            throws IOException {
        AgentStep agent = (AgentStep) StepDefaults.create(StepKind.AGENT, "agent-1");

        Path promptFile = projectRoot.resolve(agent.promptTemplate());
        Files.createDirectories(promptFile.getParent());
        Files.writeString(promptFile, AgentPromptScaffold.render(agent.id()));

        PipelineDefinition pipeline = new PipelineDefinition("pipeline", 1, List.of(agent));

        InMemoryStateStore stateStore = new InMemoryStateStore();
        ObjectMapper mapper = new ObjectMapper();
        FixtureAgentRuntimePort agentRuntime = new FixtureAgentRuntimePort(inv -> {
            ObjectNode json = mapper.createObjectNode();
            json.put("step_id", agent.id());
            return new AgentResult(0, Optional.of(json), new TokenUsage(1, 1), Path.of("raw.log"));
        });

        try (PipelineEngine engine = new PipelineEngine(stateStore, agentRuntime, FixtureScriptRunnerPort.alwaysOk())) {
            RunId runId = engine.start(TestProjects.minimal(projectRoot), pipeline, "feature-x");

            until(() -> engine.snapshot(runId).map(s -> s.status() == RunStatus.COMPLETED).orElse(false));
            assertThat(statusOf(engine.snapshot(runId).orElseThrow(), agent.id())).isEqualTo(StepStatus.PASSED);
        }
    }

    @Test
    void freshAgentTileIsInvalidBeforeThePromptIsSeeded(@TempDir Path projectRoot) {
        StepDefinition step = StepDefaults.create(StepKind.AGENT, "agent-1");
        PipelineDefinition pipeline = new PipelineDefinition("pipeline", 1, List.of(step));

        List<PipelineError> errors = new PipelineValidator().validate(pipeline, PipelineValidator.Options.withRoot(projectRoot));

        assertThat(errors).isNotEmpty(); // "prompt file not found" — badges the tile, as FR-2.6 expects
    }
}
