package dev.forgeide.ui.run;

import dev.forgeide.core.run.RunSnapshot;
import dev.forgeide.core.run.RunStatus;
import dev.forgeide.core.run.StepStatus;

/** Per-run summary for the feature run-history list (SDD FR-7.9). Token totals are dropped for
 * this task — see T10 plan's known limitations (ai-logs is not runId-scoped). */
public final class RunListSummary {

    private RunListSummary() {
    }

    public record Summary(RunStatus status, int totalSteps, int passedSteps, int failedSteps, int totalIterations) {
    }

    public static Summary summarize(RunSnapshot snapshot) {
        int passed = 0;
        int failed = 0;
        int iterations = 0;
        for (var step : snapshot.steps()) {
            if (step.status() == StepStatus.PASSED) {
                passed++;
            } else if (step.status() == StepStatus.FAILED) {
                failed++;
            }
            iterations += step.iteration();
        }
        return new Summary(snapshot.status(), snapshot.steps().size(), passed, failed, iterations);
    }
}
