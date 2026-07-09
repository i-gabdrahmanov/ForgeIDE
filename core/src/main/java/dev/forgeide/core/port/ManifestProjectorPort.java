package dev.forgeide.core.port;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.forgeide.core.run.RunSnapshot;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Manifest-projection port (SD §4, SDD FR-7.2/SR-2/Т-1). Before every agent phase the engine
 * writes a copy of the run's state, in the {@code pipeline-state} Forge format, to
 * {@code <project>/ground/statements/<pipeline>/<feature>/manifest.json} — hooks running inside
 * the agent process itself (tdd-guard, sod-enforcer, ...) resolve the active step from that copy
 * as a second line of defense, without the IDE ever handing the agent the real source of truth
 * ({@link StateStore}) or its on-disk location (SR-1).
 *
 * <p>Implemented in {@code runtime} against real project directories; {@code core} only knows
 * the contract, same split as {@link StateStore}.
 */
public interface ManifestProjectorPort {

    /** No-op implementation for engines/tests that don't exercise manifest projection — keeps
     * this port from forcing every unrelated test to plumb a filesystem fixture through. */
    ManifestProjectorPort NOOP = new ManifestProjectorPort() {
        @Override
        public String project(Path projectRoot, String pipelineId, String featureSlug, RunSnapshot snapshot) {
            return "";
        }

        @Override
        public Optional<String> verifyAndRestore(Path projectRoot, String pipelineId, String featureSlug,
                                                   RunSnapshot snapshot, String expectedHash) {
            return Optional.empty();
        }

        @Override
        public Optional<ObjectNode> readOrigin(Path projectRoot, String pipelineId, String featureSlug, String stepId) {
            return Optional.empty();
        }
    };

    /**
     * Writes the projection derived from {@code snapshot} and returns the SHA-256 hash of its
     * canonical form (SR-2), to be handed back to {@link #verifyAndRestore} once the phase ends.
     */
    String project(Path projectRoot, String pipelineId, String featureSlug, RunSnapshot snapshot);

    /**
     * Re-hashes the projection currently on disk. A match returns {@link Optional#empty()}. A
     * mismatch means something other than this port wrote to the projection during the phase
     * (Т-1: the model editing its own manifest to close out steps) — the projection is rewritten
     * from {@code snapshot} (the SoT) and a human-readable diff of the tampered content vs. the
     * restored content is returned, for the {@code incident.tamper} audit payload.
     */
    Optional<String> verifyAndRestore(Path projectRoot, String pipelineId, String featureSlug,
                                       RunSnapshot snapshot, String expectedHash);

    /**
     * Best-effort read of the {@code _origins/<stepId>.json} evidence record an external
     * SubagentStop hook (state-recorder) may have left next to the projection for this step.
     * Read-only signal for the audit trail (T15 scope: "движок читает как evidence, но переходы
     * делает сам") — never influences a step's PASSED/FAILED outcome, which stays driven by the
     * engine's own artifact/budget/judge checks. Empty if no such record exists or it fails to parse.
     */
    Optional<ObjectNode> readOrigin(Path projectRoot, String pipelineId, String featureSlug, String stepId);
}
