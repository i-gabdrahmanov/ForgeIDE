package dev.forgeide.core.port;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Scope-diff (SDD SR-6/Т-4/Т-13): the engine snapshots the working tree right before an agent
 * phase starts and, once it ends, compares what actually changed against the step's own {@code
 * allowed_write} glob masks. Anything touched outside those masks — including a HEAD that moved
 * (a local commit/reset the phase had no business performing; only an {@code outward} step may
 * change history, and only after a gate) — is a scope violation and fails the step {@code
 * FAILED(scope)} before any other verdict runs.
 *
 * <p>Implemented against real git plumbing in {@code runtime} ({@code GitScopeDiff}); {@code
 * core} only knows the contract, same split as {@link StateStore} and {@link
 * ManifestProjectorPort}.
 */
public interface ScopeDiffPort {

    /** No-op implementation for engines/tests that don't exercise scope-diff — an empty snapshot
     * and never a violation, so this port doesn't force every unrelated test to plumb a git
     * fixture through. */
    ScopeDiffPort NOOP = new ScopeDiffPort() {
        @Override
        public Snapshot snapshot(Path projectRoot) {
            return new Snapshot(Map.of(), null);
        }

        @Override
        public List<String> violations(Path projectRoot, Snapshot before, List<String> allowedWrite) {
            return List.of();
        }

        @Override
        public List<String> rollback(Path projectRoot, List<String> violations) {
            return List.of();
        }
    };

    /** Working-tree state right before the phase starts: {@code git status --porcelain}-derived
     * status code per path (tracked changes and untracked files alike), plus the current HEAD
     * commit (empty if not a git repository or {@code git} is unavailable — best-effort, never
     * throws, same spirit as {@code GitDiffReader}/{@code GitHead}). */
    Snapshot snapshot(Path projectRoot);

    /**
     * Re-snapshots {@code projectRoot} and returns every path that changed since {@code before}
     * and does not match any of the {@code allowedWrite} glob masks — plus a synthetic entry if
     * HEAD itself moved. Empty means the phase stayed within its scope.
     */
    List<String> violations(Path projectRoot, Snapshot before, List<String> allowedWrite);

    /**
     * Reverts exactly the given violation entries (paths only — a HEAD-move entry is not
     * revertable this way and is skipped): a tracked/modified or deleted path is restored from
     * {@code HEAD}, an untracked path is deleted. Used by the incident dialog's "roll back
     * excess" action, strictly scoped to what scope-diff itself flagged. Returns the paths
     * actually rolled back.
     */
    List<String> rollback(Path projectRoot, List<String> violations);

    /** @param statusByPath relative path -> raw {@code git status --porcelain} status code
     *                      (e.g. {@code " M"}, {@code "??"}, {@code "AM"})
     * @param head          {@code git rev-parse HEAD}, or {@code null} if unavailable */
    record Snapshot(Map<String, String> statusByPath, String head) {
        public Snapshot {
            statusByPath = Map.copyOf(statusByPath);
        }
    }
}
