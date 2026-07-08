package dev.forgeide.core.project;

import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.PipelineParam;
import dev.forgeide.core.pipeline.ScriptStep;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectParamValidatorTest {

    private static final ScriptStep STEP = new ScriptStep("s", List.of(), List.of("echo"), Duration.ofMinutes(1));

    private static PipelineDefinition pipeline(PipelineParam... params) {
        return new PipelineDefinition("pl", 1, List.of(params), List.of(STEP));
    }

    private static ProjectDefinition project(Map<String, String> paramValues) {
        return new ProjectDefinition(ProjectId.newId(), "demo", Path.of("/repo"),
                List.of(), paramValues, CriticalityProfile.LOW, List.of());
    }

    @Test
    void flagsRequiredParamWithoutValue() {
        PipelineDefinition pipeline = pipeline(PipelineParam.required("jira_key"));
        ProjectDefinition project = project(Map.of());

        assertThat(ProjectParamValidator.missingRequiredParams(project, pipeline))
                .containsExactly("jira_key");
    }

    @Test
    void blankValueCountsAsMissing() {
        PipelineDefinition pipeline = pipeline(PipelineParam.required("jira_key"));
        ProjectDefinition project = project(Map.of("jira_key", "   "));

        assertThat(ProjectParamValidator.missingRequiredParams(project, pipeline))
                .containsExactly("jira_key");
    }

    @Test
    void filledRequiredParamIsNotMissing() {
        PipelineDefinition pipeline = pipeline(PipelineParam.required("jira_key"));
        ProjectDefinition project = project(Map.of("jira_key", "FORGE-1"));

        assertThat(ProjectParamValidator.missingRequiredParams(project, pipeline)).isEmpty();
    }

    @Test
    void optionalParamsAreNeverReported() {
        PipelineDefinition pipeline = pipeline(new PipelineParam("spec_path", false, Optional.empty()));
        ProjectDefinition project = project(Map.of());

        assertThat(ProjectParamValidator.missingRequiredParams(project, pipeline)).isEmpty();
    }
}
