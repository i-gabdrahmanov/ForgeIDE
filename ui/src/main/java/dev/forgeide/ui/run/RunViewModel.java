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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
    private Consumer<EngineEvent.QuestionsPending> questionsHandler = r -> { };

    /** T21/FR-8.4-8.5: {@code requestId -> caller's callback}, since a human can fire a second
     * dry-run/preview (after editing the check script/prompt) before the first one's reply
     * arrives — this is how each reply finds its own caller instead of the last one registered. */
    private final Map<String, Consumer<EngineEvent.JudgeDryRunResult>> judgeDryRunCallbacks = new ConcurrentHashMap<>();
    private final Map<String, Consumer<String>> promptPreviewCallbacks = new ConcurrentHashMap<>();

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

    /** Fired on the FX thread for every {@code pending_questions} round this run publishes
     * (FR-10.3: same reopen-from-canvas story as a gate — the {@code QuestionDialog} it drives
     * may pop up more than once for the same step id). */
    public void onQuestionsPending(Consumer<EngineEvent.QuestionsPending> handler) {
        this.questionsHandler = handler;
    }

    /** Answers a real gate or a judge escalation (SDD FR-5.1/FR-11.3) — same command either way;
     * {@code detail} carries an escalation's edited prompt or mandatory override reason, {@code
     * diffAcked} the FR-5.3 checkbox state (ignored by the engine unless the gate is R2-risk). */
    public void answerGate(String stepId, String answer, Optional<String> detail, boolean diffAcked) {
        engine.submit(new EngineCommand.GateAnswered(runId, stepId, answer, currentUser(), Instant.now(),
                detail, diffAcked));
    }

    /** Answers a {@code pending_questions} round (FR-10.4) — same "who/when" capture as a gate. */
    public void answerQuestions(String stepId, Map<String, String> answers) {
        engine.submit(new EngineCommand.QuestionsAnswered(runId, stepId, answers, currentUser(), Instant.now()));
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

    /** T20/FR-8.2: trusted-path prompt edit while this run is live — applies from the step's next
     * dispatch only, audited by the engine as {@code prompt.edited}. */
    public void editPrompt(String stepId, String content) {
        engine.submit(new EngineCommand.PromptEdited(runId, stepId, content, currentUser(), Instant.now()));
    }

    /** T20/FR-8.3: trusted-path judge/hook script edit while this run is live — routes through
     * {@code HarnessGuardPort#edit} so the save itself becomes the new baseline instead of
     * registering as harness drift, audited as {@code harness.edited}. */
    public void editHarness(String relativePath, String content) {
        engine.submit(new EngineCommand.HarnessEdited(runId, relativePath, content, currentUser(), Instant.now()));
    }

    /**
     * T21/FR-8.4: "прогнать судью" — the reply (never persisted to {@code run.json}, see {@code
     * PipelineEngine#handleJudgeDryRunCompleted}) is delivered to {@code onResult} on the FX
     * thread exactly once.
     */
    public void requestJudgeDryRun(String judgeStepId, Consumer<EngineEvent.JudgeDryRunResult> onResult) {
        String requestId = UUID.randomUUID().toString();
        judgeDryRunCallbacks.put(requestId, onResult);
        engine.submit(new EngineCommand.JudgeDryRunRequested(runId, judgeStepId, requestId));
    }

    /** T21/FR-8.5: preview the prompt {@code stepId} would actually receive on its next dispatch. */
    public void requestPromptPreview(String stepId, Consumer<String> onRendered) {
        String requestId = UUID.randomUUID().toString();
        promptPreviewCallbacks.put(requestId, onRendered);
        engine.submit(new EngineCommand.PromptPreviewRequested(runId, stepId, requestId));
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
        } else if (event instanceof EngineEvent.QuestionsPending request && request.runId().equals(runId)) {
            Platform.runLater(() -> questionsHandler.accept(request));
        } else if (event instanceof EngineEvent.JudgeDryRunResult result && result.runId().equals(runId)) {
            Consumer<EngineEvent.JudgeDryRunResult> callback = judgeDryRunCallbacks.remove(result.requestId());
            if (callback != null) {
                Platform.runLater(() -> callback.accept(result));
            }
        } else if (event instanceof EngineEvent.PromptPreviewReady ready && ready.runId().equals(runId)) {
            Consumer<String> callback = promptPreviewCallbacks.remove(ready.requestId());
            if (callback != null) {
                Platform.runLater(() -> callback.accept(ready.renderedPrompt()));
            }
        }
    }
}
