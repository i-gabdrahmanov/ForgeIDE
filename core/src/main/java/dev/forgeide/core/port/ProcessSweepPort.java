package dev.forgeide.core.port;

import java.nio.file.Path;
import java.util.List;

/**
 * Orphan-process sweep (SDD SR-9/Т-9): a phase that {@code nohup}/{@code setsid}'d a background
 * process out of its own process group leaves it running even after the phase's own group is
 * killed. Consulted by the engine right after every agent phase — regardless of that phase's own
 * outcome — so an escapee with its cwd under the project is force-killed and the sweep becomes an
 * auditable incident (SD §4: "аудит пишет только движок"), not a silent cleanup.
 *
 * <p>Implemented against real process/cwd introspection in {@code runtime} ({@code ProcessRunner}
 * already exposes exactly this as {@code sweepOrphans}); {@code core} only knows the contract,
 * same split as {@link ScopeDiffPort}/{@link ManifestProjectorPort}.
 */
public interface ProcessSweepPort {

    /** No-op implementation for engines/tests that don't exercise the sweep — nothing is ever
     * found, so this port doesn't force every unrelated test to plumb a process fixture through. */
    ProcessSweepPort NOOP = projectRoot -> List.of();

    /**
     * Force-kills every process (other than this JVM) whose current working directory sits under
     * {@code projectRoot} and returns their pids. Best-effort and safe to call unconditionally —
     * an empty result is the overwhelming common case (a phase that behaved).
     */
    List<Long> sweepOrphans(Path projectRoot);
}
