package dev.forgeide.core.pipeline.edit;

import dev.forgeide.core.engine.PipelineEngine;
import dev.forgeide.core.engine.support.FixtureAgentRuntimePort;
import dev.forgeide.core.engine.support.FixtureScriptRunnerPort;
import dev.forgeide.core.engine.support.InMemoryStateStore;
import dev.forgeide.core.engine.support.TestProjects;
import dev.forgeide.core.pipeline.AgentStep;
import dev.forgeide.core.pipeline.JudgeStep;
import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.ScriptStep;
import dev.forgeide.core.pipeline.validation.PipelineValidator;
import dev.forgeide.core.pipeline.yaml.PipelineYaml;
import dev.forgeide.core.run.RunId;
import dev.forgeide.core.run.RunStatus;
import dev.forgeide.core.run.StepStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static dev.forgeide.core.engine.support.Await.until;
import static dev.forgeide.core.engine.support.Snapshots.statusOf;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * T22 acceptance: mirrors the GWT in the task ("agent+judge собраны... без промпта — бейдж...
 * после заполнения — зелёно") purely through the building blocks a canvas drag&drop/edge-drag
 * would invoke — {@link StepDefaults}, {@link PipelineEdits}, {@link PipelineDocument} — plus a
 * second pipeline proving a constructor-assembled definition runs end-to-end on the fixture
 * runtime once it is valid.
 */
class PipelineConstructorEndToEndTest {

    @Test
    void agentPlusJudgeAssembledFromThePaletteIsInvalidUntilThePromptExists(@TempDir Path repo) throws IOException {
        PipelineDocument document = new PipelineDocument(new PipelineDefinition("p", 1, List.of()),
                PipelineValidator.Options.withRoot(repo));

        String agentId = StepIds.next(StepKind.AGENT, Set.of());
        document.apply(PipelineEdits.addStep(StepDefaults.create(StepKind.AGENT, agentId)));
        String judgeId = StepIds.next(StepKind.JUDGE, Set.of(agentId));
        document.apply(PipelineEdits.addStep(StepDefaults.create(StepKind.JUDGE, judgeId)));

        // FR-2.5 "протяжка ребра между плитками = depends_on"
        document.apply(PipelineEdits.addDependency(agentId, judgeId));

        // GWT: agent+judge connected, but the agent has no prompt and the judge no target yet.
        assertThat(document.isValid()).isFalse();

        JudgeStep judge = (JudgeStep) document.current().step(judgeId);
        document.apply(PipelineEdits.replaceStep(judgeId,
                new JudgeStep(judge.id(), judge.dependsOn(), agentId, judge.llmJudge(),
                        judge.deterministicCheck(), judge.failPolicy())));
        assertThat(document.isValid()).isFalse(); // still no prompt file for the agent tile

        AgentStep agent = (AgentStep) document.current().step(agentId);
        Path promptPath = Path.of("prompts").resolve(agentId + ".md");
        document.apply(PipelineEdits.replaceStep(agentId,
                new AgentStep(agent.id(), agent.dependsOn(), agent.runtimeRef(), promptPath,
                        agent.expectedArtifacts(), agent.allowedWrite(), agent.envScope(), agent.retry(), agent.budget())));
        assertThat(document.isValid()).isFalse(); // path set, file still doesn't exist on disk

        Files.createDirectories(repo.resolve("prompts"));
        Files.writeString(repo.resolve(promptPath), "do the thing\n");
        document.revalidate(); // the file changed on disk, not the model — apply() wouldn't see it
        assertThat(document.isValid()).isTrue();
    }

    @Test
    void aPipelineAssembledThroughTheConstructorRunsEndToEndOnTheFixtureRuntime(@TempDir Path repo) {
        PipelineDocument document = new PipelineDocument(new PipelineDefinition("p", 1, List.of()));

        String a = StepIds.next(StepKind.SCRIPT, Set.of());
        document.apply(PipelineEdits.addStep(withCommand(StepDefaults.create(StepKind.SCRIPT, a), "build")));
        String b = StepIds.next(StepKind.SCRIPT, Set.of(a));
        document.apply(PipelineEdits.addStep(withCommand(StepDefaults.create(StepKind.SCRIPT, b), "deploy")));
        document.apply(PipelineEdits.addDependency(a, b));

        assertThat(document.isValid()).isTrue();

        String yaml = new PipelineYaml().serialize(document.current());
        PipelineDefinition reloaded = new PipelineYaml().parse(yaml); // FR-2.7 round trip

        InMemoryStateStore stateStore = new InMemoryStateStore();
        FixtureScriptRunnerPort scriptRunner = FixtureScriptRunnerPort.alwaysOk();
        FixtureAgentRuntimePort agentRuntime = new FixtureAgentRuntimePort(inv -> {
            throw new AssertionError("this pipeline has no agent step");
        });

        try (PipelineEngine engine = new PipelineEngine(stateStore, agentRuntime, scriptRunner)) {
            RunId runId = engine.start(TestProjects.minimal(repo), reloaded, "feature-x");
            until(() -> engine.snapshot(runId).map(s -> s.status() == RunStatus.COMPLETED).orElse(false));

            var snapshot = engine.snapshot(runId).orElseThrow();
            assertThat(statusOf(snapshot, a)).isEqualTo(StepStatus.PASSED);
            assertThat(statusOf(snapshot, b)).isEqualTo(StepStatus.PASSED);
        }
    }

    private static ScriptStep withCommand(dev.forgeide.core.pipeline.StepDefinition step, String command) {
        ScriptStep s = (ScriptStep) step;
        return new ScriptStep(s.id(), s.dependsOn(), List.of(command), s.timeout(), s.retry());
    }
}
