package dev.forgeide.core.pipeline.library;

import dev.forgeide.core.pipeline.StepDefinition;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A saved tile or subgraph (FR-2.9): the step(s) as they existed at save time, plus every
 * prompt/script file they referenced, captured as {@code project-relative-path-at-save-time ->
 * content} so an insert into a different project can rebind both id and path without touching the
 * source project again. {@link #steps()} may already carry {@code depends_on}/{@code target}
 * edges pointing outside this set — {@link LibraryTileInsertion} is what drops those (an
 * "independent copy", same spirit as {@code PipelineEdits#duplicateStep}).
 */
public record LibraryTile(LibraryTileMetadata metadata, List<StepDefinition> steps, Map<String, String> files) {

    public LibraryTile {
        Objects.requireNonNull(metadata, "metadata");
        steps = List.copyOf(steps);
        files = Map.copyOf(files);
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("library tile requires at least one step");
        }
    }
}
