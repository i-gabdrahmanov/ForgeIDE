package dev.forgeide.core.port;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Harness-integrity port (SD §4/§6.1, SDD FR-1.4/FR-8.3/SR-7/SR-8/Т-7): guards the project's
 * agent harness — {@code <project>/.gigacode/{hooks,skills}/} and {@code
 * <project>/.gigacode/settings.hooks.json} — the trusted-code-in-untrusted-environment layer an
 * agent phase runs with full OS-user write access next to.
 *
 * <ul>
 *   <li>{@link #deploy} builds the hash-manifest, copies the harness into the IDE's own {@code
 *       ~/.forgeide/harness-cache/<hash>/} and runs {@code preflight.py} (FR-1.4).
 *   <li>{@link #checkDrift} is consulted before every agent phase (SR-8): a mismatch against the
 *       last deployed/accepted baseline means something other than the IDE wrote to the harness
 *       since — a run in progress must stop, not just fail one step, since the untrusted phase
 *       that caused it may already have compromised the very scripts that grade later phases.
 *   <li>{@link #resolveFromCache} is the SR-7 prevention half of Т-7: a judge/preflight script
 *       path that resolves under the harness is executed from the cache copy, never the working
 *       copy, so a compromised phase editing the very script that grades it has no effect.
 *   <li>{@link #edit} is the FR-8.3 trusted path a harness editor (T20) calls: atomically updates
 *       the working copy, the cache and the baseline hash-manifest together, so a save through the
 *       IDE is legitimate content, never drift.
 * </ul>
 *
 * <p>Implemented against the real filesystem in {@code runtime}; {@code core} only knows the
 * contract, same split as {@link ManifestProjectorPort} and {@link ScopeDiffPort}.
 */
public interface HarnessGuardPort {

    /** No-op implementation for engines/tests that don't exercise harness integrity — preflight
     * always passes, drift never trips, cache resolution is the identity — so this port doesn't
     * force every unrelated test to plumb a harness fixture through. */
    HarnessGuardPort NOOP = new HarnessGuardPort() {
        @Override
        public DeployResult deploy(Path projectRoot) {
            return new DeployResult("", true, "");
        }

        @Override
        public PreflightStatus preflightStatus(Path projectRoot) {
            return new PreflightStatus(true, "", Optional.empty());
        }

        @Override
        public Optional<Drift> checkDrift(Path projectRoot) {
            return Optional.empty();
        }

        @Override
        public void acceptDrift(Path projectRoot) {
        }

        @Override
        public List<String> rollbackDrift(Path projectRoot) {
            return List.of();
        }

        @Override
        public List<String> resolveFromCache(Path projectRoot, List<String> command) {
            return List.copyOf(command);
        }

        @Override
        public HarnessEditResult edit(Path projectRoot, String relativePath, String content) {
            return new HarnessEditResult("", "", "");
        }
    };

    /**
     * Deploy button (FR-1.4): hashes the current harness, copies it into the content-addressed
     * IDE cache, then runs {@code deploy.sh}/{@code preflight.py} and persists the result as the
     * new baseline. Idempotent — deploying unchanged content re-runs preflight but reuses the
     * same cache entry.
     */
    DeployResult deploy(Path projectRoot);

    /**
     * The last {@link #deploy} outcome for {@code projectRoot} (empty/never-deployed counts as
     * not passed). Consulted once, at run start (FR-1.4's GWT) — {@code
     * dev.forgeide.core.engine.PipelineEngine#start} refuses to begin a run otherwise.
     */
    PreflightStatus preflightStatus(Path projectRoot);

    /**
     * Re-scans the harness and compares it against the last deployed/accepted baseline. Empty
     * means no drift. A present {@link Drift} carries a diff of the changed files for the
     * {@code STOPPED(harness-drift)} audit payload (SR-8's GWT).
     */
    Optional<Drift> checkDrift(Path projectRoot);

    /**
     * "Принять дифф": re-scans the harness and adopts its current content as the new baseline
     * (fresh cache entry, fresh hash-manifest) — the human has looked at the diff {@link
     * #checkDrift} returned and judged it legitimate.
     */
    void acceptDrift(Path projectRoot);

    /**
     * "Откатить": restores every harness file from the last deployed/accepted cache copy,
     * discarding whatever changed the working copy since. Returns the relative paths actually
     * restored (added-since-baseline files are deleted, not counted here — same "paths only"
     * shape as {@link ScopeDiffPort#rollback}).
     */
    List<String> rollbackDrift(Path projectRoot);

    /**
     * Rewrites {@code command}'s script-path argument(s) that resolve under the project's
     * harness to the equivalent path inside the IDE's cache copy of the current baseline (SR-7);
     * every other token (interpreter, flags, project-relative args) passes through unchanged.
     */
    List<String> resolveFromCache(Path projectRoot, List<String> command);

    /**
     * FR-8.3 trusted-edit path: atomically (1) writes {@code content} to {@code relativePath} in
     * the working copy, (2) refreshes the cache copy, (3) recomputes and persists the baseline
     * hash-manifest — a save through this method can never itself register as drift.
     */
    HarnessEditResult edit(Path projectRoot, String relativePath, String content);

    /** @param preflightOutput combined stdout/stderr of the {@code preflight.py} run, shown as
     *                          the "enforcement off" diagnostic when {@code preflightPassed} is
     *                          {@code false}. */
    record DeployResult(String hash, boolean preflightPassed, String preflightOutput) {
    }

    /** @param passed     whether a harness has ever been deployed and its last preflight passed.
     * @param detail      human-readable diagnostic — preflight output, or "not deployed" if none.
     * @param deployedAt  T37: when the current baseline was deployed (empty if never deployed) —
     *                    shown on the project card next to {@code passed}/{@code detail}. */
    record PreflightStatus(boolean passed, String detail, Optional<Instant> deployedAt) {
        public PreflightStatus {
            Objects.requireNonNull(detail, "detail");
            Objects.requireNonNull(deployedAt, "deployedAt");
        }
    }

    /** @param baselineHash the hash-manifest recorded at the last deploy/accept.
     * @param currentHash   the hash-manifest of the harness as it stands right now.
     * @param diff          human-readable list of added/removed/modified harness files. */
    record Drift(String baselineHash, String currentHash, String diff) {
    }

    record HarnessEditResult(String oldHash, String newHash, String diff) {
    }
}
