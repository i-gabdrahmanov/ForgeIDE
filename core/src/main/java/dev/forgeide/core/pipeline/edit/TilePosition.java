package dev.forgeide.core.pipeline.edit;

/**
 * Where a tile sits on the T22 constructor canvas. Canvas-only bookkeeping — {@code
 * pipeline.yaml} has no x/y fields (SDD FR-2.1's format is unchanged by the constructor), so
 * positions live beside a {@link PipelineDocument}, not inside its undo/redo history.
 */
public record TilePosition(double x, double y) {
}
