package dev.forgeide.core.pipeline.validation;

import java.util.Objects;

/**
 * A single validation problem carrying its coordinate so the canvas (T05) can badge the
 * offending tile and field (SDD FR-2.3). A blank {@code stepId} marks a pipeline-level
 * problem (e.g. a missing top-level field).
 *
 * @param stepId id of the offending step, or {@code ""} for a pipeline-level error
 * @param field  the field the error is about (e.g. {@code depends_on}, {@code prompt})
 */
public record PipelineError(String stepId, String field, String message) {

    public PipelineError {
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(message, "message");
    }

    public static PipelineError atPipeline(String field, String message) {
        return new PipelineError("", field, message);
    }

    public static PipelineError atStep(String stepId, String field, String message) {
        return new PipelineError(stepId, field, message);
    }

    public boolean isPipelineLevel() {
        return stepId.isEmpty();
    }

    @Override
    public String toString() {
        String where = stepId.isEmpty() ? "pipeline" : "step '" + stepId + "'";
        return where + " [" + field + "]: " + message;
    }
}
