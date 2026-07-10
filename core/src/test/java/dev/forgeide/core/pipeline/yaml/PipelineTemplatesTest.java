package dev.forgeide.core.pipeline.yaml;

import dev.forgeide.core.pipeline.JudgeStep;
import dev.forgeide.core.pipeline.OutwardAction;
import dev.forgeide.core.pipeline.OutwardStep;
import dev.forgeide.core.pipeline.PerTaskLoop;
import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.validation.PipelineValidator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T24: {@code feature-pipeline} is the second bundled template (SD §8, FR-9.1) and the only
 * one exercising {@code per_task_loop} — the acyclic/judge-before-outward checks only see
 * top-level steps (SDD FR-2.3), so the loop must be closed off by a top-level judge before
 * {@code gate-deliver}/{@code fp-deliver}, same as {@link PipelineYamlTest#parsesForgeliteTemplate()}
 * documents for forgelite.
 */
class PipelineTemplatesTest {

    @Test
    void featurePipelineParsesAndValidates() {
        PipelineDefinition def = PipelineTemplates.featurePipeline();

        assertThat(def.id()).isEqualTo("feature-pipeline");
        assertThat(def.version()).isEqualTo(1);
        assertThat(new PipelineValidator().validate(def)).isEmpty();
    }

    @Test
    void featurePipelineDeclaresTopLevelSteps() {
        PipelineDefinition def = PipelineTemplates.featurePipeline();

        assertThat(def.steps()).extracting(s -> s.id())
                .containsExactly("fp-brd", "judge-brd", "gate-brd", "fp-ground", "fp-design",
                        "gate-design", "fp-jira", "fp-tasks", "judge-delivery", "gate-deliver", "fp-deliver");
    }

    @Test
    void featurePipelineLoopsPerTaskPlanEntry() {
        PipelineDefinition def = PipelineTemplates.featurePipeline();

        PerTaskLoop loop = (PerTaskLoop) def.step("fp-tasks");
        assertThat(loop.template()).extracting(s -> s.id())
                .containsExactly("fp-red", "judge-red", "fp-green", "judge-build");
    }

    @Test
    void deliveryIsGatedByATopLevelJudgeWithDeterministicCheck() {
        PipelineDefinition def = PipelineTemplates.featurePipeline();

        OutwardStep deliver = (OutwardStep) def.step("fp-deliver");
        assertThat(deliver.actions()).containsExactly(OutwardAction.GIT_PUSH, OutwardAction.CREATE_PR);

        JudgeStep judgeDelivery = (JudgeStep) def.step("judge-delivery");
        assertThat(judgeDelivery.targetStepId()).isEqualTo("fp-tasks");
        assertThat(judgeDelivery.deterministicCheck()).isPresent();
    }

    @Test
    void featurePipelineYamlRoundTrips() {
        PipelineYaml yaml = new PipelineYaml();
        PipelineDefinition def = PipelineTemplates.featurePipeline();

        PipelineDefinition roundTripped = yaml.parse(yaml.serialize(def));

        assertThat(roundTripped).isEqualTo(def);
    }
}
