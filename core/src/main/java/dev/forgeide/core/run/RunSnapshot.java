package dev.forgeide.core.run;

import java.util.List;
import java.util.Optional;

/**
 * Immutable copy of a {@link PipelineRun} handed out over the event bus (SD §2).
 * The mutable run is only ever touched from the engine's actor thread.
 */
public record RunSnapshot(
        RunId runId,
        String featureSlug,
        RunStatus status,
        Optional<RunHaltReason> haltReason,
        List<StepSnapshot> steps
) {

    public RunSnapshot {
        steps = List.copyOf(steps);
    }
}
