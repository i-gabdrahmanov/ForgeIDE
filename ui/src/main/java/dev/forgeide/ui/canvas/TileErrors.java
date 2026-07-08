package dev.forgeide.ui.canvas;

import dev.forgeide.core.pipeline.validation.PipelineError;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Groups flat {@link PipelineError} lists by the coordinate the canvas needs to badge (FR-2.3):
 * per-step for tile badges, pipeline-level for a banner above the graph. Pure — no JavaFX — so
 * the grouping and text formatting are unit-testable without a display.
 */
public final class TileErrors {

    private TileErrors() {
    }

    public static Map<String, List<PipelineError>> byStep(List<PipelineError> errors) {
        Map<String, List<PipelineError>> grouped = new LinkedHashMap<>();
        for (PipelineError error : errors) {
            if (!error.isPipelineLevel()) {
                grouped.computeIfAbsent(error.stepId(), id -> new ArrayList<>()).add(error);
            }
        }
        return grouped;
    }

    public static List<PipelineError> pipelineLevel(List<PipelineError> errors) {
        return errors.stream().filter(PipelineError::isPipelineLevel).toList();
    }

    /** Tooltip/badge text for one tile: {@code "field: message"} per error, newline-separated. */
    public static String badgeText(List<PipelineError> stepErrors) {
        return stepErrors.stream()
                .map(e -> e.field() + ": " + e.message())
                .collect(Collectors.joining("\n"));
    }
}
