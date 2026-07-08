package dev.forgeide.ui.run;

import dev.forgeide.core.engine.PipelineEngine;
import dev.forgeide.core.event.EngineEvent;
import dev.forgeide.core.run.RunId;
import dev.forgeide.core.run.RunStatus;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * One {@link PipelineEngine} per launched run: {@code FileStateStore}'s root is fixed at
 * construction to a single project+pipeline (SD §4), so a single app-wide engine cannot serve
 * every project the IDE has open. Registers an engine when "Start run" launches it; closes and
 * evicts it only once its run reaches a terminal {@link RunStatus} — <b>not</b> on mere
 * navigation away, so a run keeps executing in the background if the user goes back to the
 * project list. {@link #closeAll()} is the app-shutdown fallback for runs still in flight.
 */
public final class RunEngineRegistry {

    private final Map<RunId, PipelineEngine> engines = new ConcurrentHashMap<>();

    public void register(RunId runId, PipelineEngine engine) {
        engines.put(runId, engine);
        AtomicReference<Consumer<EngineEvent>> listenerRef = new AtomicReference<>();
        Consumer<EngineEvent> listener = event -> {
            if (event instanceof EngineEvent.RunUpdated updated && updated.runId().equals(runId)
                    && isTerminal(updated.snapshot().status())) {
                engine.unsubscribe(listenerRef.get());
                PipelineEngine removed = engines.remove(runId);
                if (removed != null) {
                    removed.close();
                }
            }
        };
        listenerRef.set(listener);
        engine.subscribe(listener);
    }

    /** App-shutdown fallback: closes every engine still registered (runs still in progress). */
    public void closeAll() {
        engines.values().forEach(PipelineEngine::close);
        engines.clear();
    }

    private static boolean isTerminal(RunStatus status) {
        return status == RunStatus.COMPLETED || status == RunStatus.CANCELLED || status == RunStatus.STOPPED;
    }
}
