package dev.forgeide.core.pipeline.edit;

import dev.forgeide.core.pipeline.PipelineDefinition;

/**
 * One entry of the "командный стек над моделью" (FR-2.5): a pure function from the current
 * model to the next one. Records are immutable, so a command needs no inverse — {@link
 * PipelineDocument} undoes by remembering the state a command was applied to, not by running
 * the command backwards.
 */
@FunctionalInterface
public interface PipelineEdit {
    PipelineDefinition apply(PipelineDefinition current);
}
