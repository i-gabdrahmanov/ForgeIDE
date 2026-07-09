package dev.forgeide.ui.run;

import dev.forgeide.core.engine.PipelineEngine;
import dev.forgeide.core.event.EngineCommand;
import dev.forgeide.core.event.EngineEvent;
import dev.forgeide.core.run.RunId;
import dev.forgeide.core.run.RunSnapshot;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Bridges one run's {@link PipelineEngine} events to the FX thread (SD §7 MVVM: "мост в FX-поток
 * — Platform.runLater"). {@link PipelineEngine#subscribe} fires for every run the engine
 * manages, so this filters down to the one {@code runId} it was constructed for.
 */
public final class RunViewModel {

    private final PipelineEngine engine;
    private final RunId runId;
    private final ObjectProperty<RunSnapshot> snapshot = new SimpleObjectProperty<>();
    private final Consumer<EngineEvent> listener = this::onEvent;
    private Consumer<EngineEvent.GateRequest> gateHandler = r -> { };

    public RunViewModel(PipelineEngine engine, RunId runId) {
        this.engine = engine;
        this.runId = runId;
        engine.snapshot(runId).ifPresent(snapshot::set);
        engine.subscribe(listener);
    }

    public ObjectProperty<RunSnapshot> snapshotProperty() {
        return snapshot;
    }

    /** Fired on the FX thread for every gate request/escalation this run publishes (T12: the
     * {@code GateDialog} it drives may pop up more than once for the same step id — dismissed
     * and reopened from the canvas — since the engine only ever asks once per {@code
     * WAITING_GATE} entry). */
    public void onGateRequest(Consumer<EngineEvent.GateRequest> handler) {
        this.gateHandler = handler;
    }

    /** Answers a real gate or a judge escalation (SDD FR-5.1/FR-11.3) — same command either way;
     * {@code detail} carries an escalation's edited prompt or mandatory override reason, {@code
     * diffAcked} the FR-5.3 checkbox state (ignored by the engine unless the gate is R2-risk). */
    public void answerGate(String stepId, String answer, Optional<String> detail, boolean diffAcked) {
        engine.submit(new EngineCommand.GateAnswered(runId, stepId, answer, currentUser(), Instant.now(),
                detail, diffAcked));
    }

    private static String currentUser() {
        return System.getProperty("user.name", "unknown");
    }

    public void cancel() {
        engine.submit(new EngineCommand.CancelRun(runId));
    }

    /** Manual retry of a FAILED step (T11 scope; the engine itself refuses one still pending
     * investigation as an incident — SDD FR-11's tamper/scope class). */
    public void retry(String stepId) {
        engine.submit(new EngineCommand.RetryStep(runId, stepId));
    }

    /** Stops listening — call when the {@link RunView} is closed/navigated away from. */
    public void dispose() {
        engine.unsubscribe(listener);
    }

    private void onEvent(EngineEvent event) {
        if (event instanceof EngineEvent.RunUpdated updated && updated.runId().equals(runId)) {
            Platform.runLater(() -> snapshot.set(updated.snapshot()));
        } else if (event instanceof EngineEvent.GateRequest request && request.runId().equals(runId)) {
            Platform.runLater(() -> gateHandler.accept(request));
        }
    }
}
