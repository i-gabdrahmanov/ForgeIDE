package dev.forgeide.core.run;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * FR-3.4: a step still {@code RUNNING} in the persisted SoT was executing on a worker thread of
 * an IDE process that is now gone — there is no way to know whether it actually finished, so it
 * becomes a terminal {@code FAILED(interrupted)} a human can retry. {@code WAITING_GATE} /
 * {@code WAITING_INPUT} steps are untouched: nothing was executing for them, they are simply
 * waiting on a human who can resume that wait exactly where the old process left it.
 *
 * <p>Pure and file-system-free by design so it is safe to call from anywhere that holds a {@link
 * RunSnapshot} — {@code runtime}'s {@code StartupRecovery} is what actually applies it to every
 * persisted run at IDE startup, the one point in time a {@code RUNNING} step in the SoT can only
 * mean "abandoned", never "someone else's engine is legitimately still working on this".
 */
public final class RunRecovery {

    private RunRecovery() {
    }

    /** Empty if {@code snapshot} needed no recovery (no step was left {@code RUNNING}). */
    public static Optional<RunSnapshot> recoverInterrupted(RunSnapshot snapshot) {
        boolean changed = false;
        List<StepSnapshot> steps = new ArrayList<>(snapshot.steps().size());
        for (StepSnapshot step : snapshot.steps()) {
            if (step.status() == StepStatus.RUNNING) {
                steps.add(new StepSnapshot(step.stepId(), StepStatus.FAILED, step.iteration(),
                        Optional.of(FailureReason.INTERRUPTED), List.of(), step.verdicts(), step.events()));
                changed = true;
            } else {
                steps.add(step);
            }
        }
        if (!changed) {
            return Optional.empty();
        }
        return Optional.of(new RunSnapshot(snapshot.runId(), snapshot.featureSlug(), snapshot.status(),
                snapshot.haltReason(), steps));
    }
}
