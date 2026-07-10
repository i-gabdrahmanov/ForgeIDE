package dev.forgeide.core.pipeline.edit;

import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.ScriptStep;
import dev.forgeide.core.pipeline.StepDefinition;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineEditsTest {

    private static ScriptStep script(String id, List<String> dependsOn) {
        return new ScriptStep(id, dependsOn, List.of("echo"), Duration.ofSeconds(5));
    }

    @Test
    void addStepAppendsToTheEnd() {
        PipelineDefinition def = new PipelineDefinition("p", 1, List.of(script("a", List.of())));
        PipelineDefinition next = PipelineEdits.addStep(script("b", List.of())).apply(def);
        assertThat(next.steps()).extracting(StepDefinition::id).containsExactly("a", "b");
    }

    @Test
    void removeStepDropsItButLeavesDanglingReferencesForTheValidatorToCatch() {
        PipelineDefinition def = new PipelineDefinition("p", 1,
                List.of(script("a", List.of()), script("b", List.of("a"))));
        PipelineDefinition next = PipelineEdits.removeStep("a").apply(def);
        assertThat(next.steps()).extracting(StepDefinition::id).containsExactly("b");
        assertThat(next.step("b").dependsOn()).containsExactly("a"); // not healed
    }

    @Test
    void duplicateStepCopiesConfigUnderANewIdWithNoIncomingEdges() {
        PipelineDefinition def = new PipelineDefinition("p", 1, List.of(script("a", List.of())));
        PipelineDefinition next = PipelineEdits.duplicateStep("a", "a-2").apply(def);
        assertThat(next.steps()).extracting(StepDefinition::id).containsExactly("a", "a-2");
        assertThat(next.step("a-2").dependsOn()).isEmpty();
        assertThat(((ScriptStep) next.step("a-2")).command()).isEqualTo(List.of("echo"));
    }

    @Test
    void replaceStepSwapsInPlace() {
        PipelineDefinition def = new PipelineDefinition("p", 1, List.of(script("a", List.of())));
        ScriptStep replacement = new ScriptStep("a", List.of(), List.of("build", "now"), Duration.ofSeconds(9));
        PipelineDefinition next = PipelineEdits.replaceStep("a", replacement).apply(def);
        assertThat(next.steps()).containsExactly(replacement);
    }

    @Test
    void addDependencyAddsFromToTosDependsOn() {
        PipelineDefinition def = new PipelineDefinition("p", 1,
                List.of(script("a", List.of()), script("b", List.of())));
        PipelineDefinition next = PipelineEdits.addDependency("a", "b").apply(def);
        assertThat(next.step("b").dependsOn()).containsExactly("a");
    }

    @Test
    void addDependencyIsANoOpOnASelfLoopOrADuplicateEdge() {
        PipelineDefinition def = new PipelineDefinition("p", 1,
                List.of(script("a", List.of()), script("b", List.of("a"))));
        assertThat(PipelineEdits.addDependency("b", "b").apply(def)).isEqualTo(def);
        assertThat(PipelineEdits.addDependency("a", "b").apply(def)).isEqualTo(def);
    }

    @Test
    void removeDependencyDropsIt() {
        PipelineDefinition def = new PipelineDefinition("p", 1,
                List.of(script("a", List.of()), script("b", List.of("a"))));
        PipelineDefinition next = PipelineEdits.removeDependency("a", "b").apply(def);
        assertThat(next.step("b").dependsOn()).isEmpty();
    }

    @Test
    void replacePipelineSwapsTheWholeModel() {
        PipelineDefinition def = new PipelineDefinition("p", 1, List.of(script("a", List.of())));
        PipelineDefinition replacement = new PipelineDefinition("p2", 2, List.of(script("z", List.of())));
        assertThat(PipelineEdits.replacePipeline(replacement).apply(def)).isEqualTo(replacement);
    }
}
