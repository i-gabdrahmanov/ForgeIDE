package dev.forgeide.runtime.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.forgeide.core.audit.AuditEvent;
import dev.forgeide.core.run.FailureReason;
import dev.forgeide.core.run.RunId;
import dev.forgeide.core.run.RunRecovery;
import dev.forgeide.core.run.RunSnapshot;
import dev.forgeide.core.run.StepStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * IDE-startup recovery (SDD FR-3.4): sweeps every run persisted under one {@link FileStateStore}
 * and turns any step a killed process left {@code RUNNING} into a terminal {@code
 * FAILED(interrupted)} via {@link RunRecovery} — the one point in the IDE's lifecycle where a
 * persisted {@code RUNNING} step can only mean "abandoned", since no {@code PipelineEngine} has
 * been constructed yet to legitimately still be working on it.
 */
public final class StartupRecovery {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StartupRecovery() {
    }

    /** @return the ids of every run this pass actually recovered (empty if the store was already clean). */
    public static List<RunId> recover(FileStateStore store) {
        List<RunId> recovered = new ArrayList<>();
        for (RunId id : store.listAllRuns()) {
            store.load(id).flatMap(RunRecovery::recoverInterrupted).ifPresent(fixed -> {
                store.save(fixed);
                store.appendAudit(recoveredAuditEvent(fixed));
                recovered.add(id);
            });
        }
        return recovered;
    }

    private static AuditEvent recoveredAuditEvent(RunSnapshot snapshot) {
        ObjectNode payload = MAPPER.createObjectNode();
        ArrayNode stepIds = payload.putArray("interruptedSteps");
        snapshot.steps().stream()
                .filter(s -> s.status() == StepStatus.FAILED
                        && s.failureReason().orElse(null) == FailureReason.INTERRUPTED)
                .forEach(s -> stepIds.add(s.stepId()));
        return new AuditEvent(0, Instant.now(), snapshot.runId(), null, 0, "run.recovered", payload, "", "");
    }
}
