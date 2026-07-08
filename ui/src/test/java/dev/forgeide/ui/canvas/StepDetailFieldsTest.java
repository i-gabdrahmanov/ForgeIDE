package dev.forgeide.ui.canvas;

import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.yaml.PipelineTemplates;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class StepDetailFieldsTest {

    private final PipelineDefinition forgelite = PipelineTemplates.forgelite();

    @Test
    void agentStepExposesPromptRuntimeAndBudget() {
        Map<String, String> fields = asMap(StepDetailFields.of(forgelite.step("lite-green")));

        assertThat(fields.get("id")).isEqualTo("lite-green");
        assertThat(fields.get("runtime")).isEqualTo("gigacode");
        assertThat(fields.get("prompt")).isEqualTo("prompts/lite-green.md");
        assertThat(fields.get("budget")).contains("4000000 tokens");
        assertThat(fields.get("depends_on")).isEqualTo("judge-red");
    }

    @Test
    void agentStepWithNoDependenciesReportsNone() {
        Map<String, String> fields = asMap(StepDetailFields.of(forgelite.step("lite-ground")));

        assertThat(fields.get("depends_on")).isEqualTo("(none)");
    }

    @Test
    void judgeStepExposesTargetAndCheckCommand() {
        Map<String, String> fields = asMap(StepDetailFields.of(forgelite.step("judge-red")));

        assertThat(fields.get("target")).isEqualTo("lite-red");
        assertThat(fields.get("check")).contains("check_tests_red.py");
        assertThat(fields.get("fail_policy")).isEqualTo("max_iterations=3");
    }

    @Test
    void outwardStepExposesActionsAndEnvScope() {
        Map<String, String> fields = asMap(StepDetailFields.of(forgelite.step("deliver")));

        assertThat(fields.get("actions")).isEqualTo("GIT_PUSH, CREATE_PR");
        assertThat(fields.get("env_scope")).isEqualTo("GIT_TOKEN");
    }

    private Map<String, String> asMap(List<StepDetailFields.Field> fields) {
        return fields.stream().collect(Collectors.toMap(StepDetailFields.Field::label, StepDetailFields.Field::value,
                (a, b) -> a));
    }
}
