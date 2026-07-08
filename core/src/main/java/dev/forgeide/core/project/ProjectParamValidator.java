package dev.forgeide.core.project;

import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.PipelineParam;

import java.util.List;

/**
 * Bridges a project's filled-in param values with the {@code required} params declared by
 * its pipeline (SDD FR-1.1; T04 acceptance: required params without a value block a run).
 * The engine itself is out of scope here (T06+) — this only computes what is missing so the
 * UI can surface it before a run is even attempted.
 */
public final class ProjectParamValidator {

    private ProjectParamValidator() {
    }

    /** Names of {@code required} pipeline params that the project has no non-blank value for. */
    public static List<String> missingRequiredParams(ProjectDefinition project, PipelineDefinition pipeline) {
        return pipeline.params().stream()
                .filter(PipelineParam::required)
                .map(PipelineParam::name)
                .filter(name -> isBlank(project.paramValues().get(name)))
                .toList();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
