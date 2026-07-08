package dev.forgeide.core.pipeline;

/** Result of looking up a step against the tile registry (SDD FR-2.4, FR-9's SKILLS-REGISTRY.md). */
public enum TileValidityStatus {
    /** No registry wired up yet, or the step is not registry-backed. */
    UNKNOWN,
    FRESH,
    /** Registry entry has expired — the canvas shows a warning badge. */
    STALE
}
