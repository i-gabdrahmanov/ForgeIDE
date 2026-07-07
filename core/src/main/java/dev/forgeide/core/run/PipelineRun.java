package dev.forgeide.core.run;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/**
 * Mutable runtime instance of a pipeline run, mutated only from the engine's actor
 * thread (SD §2). Parallel features are simply distinct instances in the same engine.
 */
public final class PipelineRun {

    private final RunId id;
    private final String featureSlug;
    private final Map<String, StepRun> steps;
    private RunStatus status;
    private RunHaltReason haltReason;

    public PipelineRun(RunId id, String featureSlug, List<String> stepIds) {
        this.id = Objects.requireNonNull(id, "id");
        this.featureSlug = Objects.requireNonNull(featureSlug, "featureSlug");
        Objects.requireNonNull(stepIds, "stepIds");
        Map<String, StepRun> map = new LinkedHashMap<>();
        for (String stepId : stepIds) {
            if (map.putIfAbsent(stepId, new StepRun(stepId)) != null) {
                throw new IllegalArgumentException("duplicate step id: " + stepId);
            }
        }
        if (map.isEmpty()) {
            throw new IllegalArgumentException("a run requires at least one step");
        }
        this.steps = map;
        this.status = RunStatus.RUNNING;
    }

    public RunId id() {
        return id;
    }

    public String featureSlug() {
        return featureSlug;
    }

    public RunStatus status() {
        return status;
    }

    public Optional<RunHaltReason> haltReason() {
        return Optional.ofNullable(haltReason);
    }

    public StepRun step(String stepId) {
        StepRun run = steps.get(stepId);
        if (run == null) {
            throw new NoSuchElementException("unknown step: " + stepId);
        }
        return run;
    }

    public List<StepRun> steps() {
        return List.copyOf(steps.values());
    }

    public void complete() {
        this.status = RunStatus.COMPLETED;
        this.haltReason = null;
    }

    public void cancel() {
        this.status = RunStatus.CANCELLED;
        this.haltReason = null;
    }

    public void pause(RunHaltReason reason) {
        this.status = RunStatus.PAUSED;
        this.haltReason = Objects.requireNonNull(reason, "reason");
    }

    public void stop(RunHaltReason reason) {
        this.status = RunStatus.STOPPED;
        this.haltReason = Objects.requireNonNull(reason, "reason");
    }

    public RunSnapshot snapshot() {
        List<StepSnapshot> stepSnapshots = steps.values().stream().map(StepRun::snapshot).toList();
        return new RunSnapshot(id, featureSlug, status, haltReason(), stepSnapshots);
    }
}
