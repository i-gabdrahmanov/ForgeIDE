package dev.forgeide.core.port;

import dev.forgeide.core.event.EngineEvent;

/**
 * UI-facing port for gates and model questions (SD §7; SDD FR-5, FR-10.3 share this
 * infrastructure). Implemented as a modal dialog in {@code ui}. Calls may block until
 * the human responds, or the dialog may be dismissed and re-opened later from the canvas.
 */
public interface GatePresenter {
    GateAnswer present(EngineEvent.GateRequest request);

    QuestionAnswers present(EngineEvent.QuestionsPending request);
}
