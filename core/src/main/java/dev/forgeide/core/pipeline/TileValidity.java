package dev.forgeide.core.pipeline;

import java.util.Objects;

/**
 * Outcome of a tile registry lookup for one step (SDD FR-2.4): owner/validity/scope metadata
 * lives in the registry (T24, {@code SKILLS-REGISTRY.md}); the canvas only needs the verdict
 * and a human-readable detail to badge the tile with.
 */
public record TileValidity(TileValidityStatus status, String detail) {

    public TileValidity {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(detail, "detail");
    }

    public static TileValidity unknown() {
        return new TileValidity(TileValidityStatus.UNKNOWN, "");
    }

    public static TileValidity fresh(String detail) {
        return new TileValidity(TileValidityStatus.FRESH, detail);
    }

    public static TileValidity stale(String detail) {
        return new TileValidity(TileValidityStatus.STALE, detail);
    }
}
