package dev.forgeide.ui.run;

import dev.forgeide.core.engine.PipelineEngine;
import dev.forgeide.core.event.EngineCommand;
import dev.forgeide.core.event.EngineEvent;
import dev.forgeide.core.run.RunId;
import dev.forgeide.core.run.RunSnapshot;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

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

    public RunViewModel(PipelineEngine engine, RunId runId) {
        this.engine = engine;
        this.runId = runId;
        engine.snapshot(runId).ifPresent(snapshot::set);
        engine.subscribe(listener);
    }

    public ObjectProperty<RunSnapshot> snapshotProperty() {
        return snapshot;
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
        }
    }
}
