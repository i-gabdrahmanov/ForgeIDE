package dev.forgeide.core.engine.support;

import dev.forgeide.core.run.RunSnapshot;
import dev.forgeide.core.run.StepStatus;

import java.util.NoSuchElementException;

public final class Snapshots {

    private Snapshots() {
    }

    public static StepStatus statusOf(RunSnapshot snapshot, String stepId) {
        return snapshot.steps().stream()
                .filter(s -> s.stepId().equals(stepId))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("no such step: " + stepId))
                .status();
    }
}
