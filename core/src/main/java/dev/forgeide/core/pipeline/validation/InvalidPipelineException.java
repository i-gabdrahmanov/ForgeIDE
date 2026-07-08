package dev.forgeide.core.pipeline.validation;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Raised by the loader when a {@code pipeline.yaml} cannot be turned into a valid
 * {@link dev.forgeide.core.pipeline.PipelineDefinition}. Carries every error found so the
 * UI can highlight all offending tiles at once rather than one per save.
 */
public final class InvalidPipelineException extends RuntimeException {

    private final transient List<PipelineError> errors;

    public InvalidPipelineException(List<PipelineError> errors) {
        super(message(errors));
        this.errors = List.copyOf(errors);
    }

    public List<PipelineError> errors() {
        return errors;
    }

    private static String message(List<PipelineError> errors) {
        if (errors.isEmpty()) {
            return "invalid pipeline";
        }
        return errors.size() + " pipeline error(s):\n  "
                + errors.stream().map(PipelineError::toString).collect(Collectors.joining("\n  "));
    }
}
