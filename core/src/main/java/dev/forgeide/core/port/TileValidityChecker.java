package dev.forgeide.core.port;

import dev.forgeide.core.pipeline.StepDefinition;
import dev.forgeide.core.pipeline.TileValidity;

/**
 * Looks up a step's validity in the tile registry (SDD FR-2.4). The registry itself
 * ({@code SKILLS-REGISTRY.md}: owner/validity/scope/evals) is built by the importer (T24); the
 * canvas (T05) only needs this port so it can badge stale tiles once a real implementation is
 * wired in — until then {@link #unknown()} is used.
 */
public interface TileValidityChecker {

    TileValidity check(StepDefinition step);

    /** No registry wired up yet — every step reports {@link TileValidity#unknown()}. */
    static TileValidityChecker unknown() {
        return step -> TileValidity.unknown();
    }
}
