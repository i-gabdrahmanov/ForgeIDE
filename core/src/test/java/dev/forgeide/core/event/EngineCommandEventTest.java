package dev.forgeide.core.event;

import dev.forgeide.core.run.RunId;
import dev.forgeide.core.run.RunSnapshot;
import dev.forgeide.core.run.RunStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class EngineCommandEventTest {

    @Test
    void commandHierarchyIsExhaustivelySwitchable() {
        RunId runId = RunId.newId();
        EngineCommand command = new EngineCommand.CancelRun(runId);

        String kind = switch (command) {
            case EngineCommand.StepCompleted ignored -> "step-completed";
            case EngineCommand.StepFailed ignored -> "step-failed";
            case EngineCommand.GateAnswered ignored -> "gate-answered";
            case EngineCommand.QuestionsAnswered ignored -> "questions-answered";
            case EngineCommand.EvidenceObserved ignored -> "evidence-observed";
            case EngineCommand.OutwardCompleted ignored -> "outward-completed";
            case EngineCommand.CancelRun ignored -> "cancel-run";
            case EngineCommand.RetryStep ignored -> "retry-step";
        };

        assertThat(kind).isEqualTo("cancel-run");
        assertThat(command.runId()).isEqualTo(runId);
    }

    @Test
    void eventHierarchyIsExhaustivelySwitchableAndRunUpdatedDerivesRunId() {
        RunId runId = RunId.newId();
        RunSnapshot snapshot = new RunSnapshot(runId, "feature-x", RunStatus.RUNNING, Optional.empty(), List.of());
        EngineEvent event = new EngineEvent.RunUpdated(snapshot);

        String kind = switch (event) {
            case EngineEvent.RunUpdated ignored -> "run-updated";
            case EngineEvent.GateRequest ignored -> "gate-request";
            case EngineEvent.QuestionsPending ignored -> "questions-pending";
            case EngineEvent.IncidentRaised ignored -> "incident-raised";
        };

        assertThat(kind).isEqualTo("run-updated");
        assertThat(event.runId()).isEqualTo(runId);
    }
}
