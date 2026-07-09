package dev.forgeide.ui.run;

import dev.forgeide.core.audit.AuditEvent;
import dev.forgeide.core.engine.PipelineEngine;
import dev.forgeide.core.event.EngineEvent;
import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.StepDefinition;
import dev.forgeide.core.pipeline.validation.PipelineError;
import dev.forgeide.core.port.StateStore;
import dev.forgeide.core.port.TileValidityChecker;
import dev.forgeide.core.project.ProjectDefinition;
import dev.forgeide.core.run.FailureReason;
import dev.forgeide.core.run.RunId;
import dev.forgeide.core.run.RunSnapshot;
import dev.forgeide.core.run.RunStatus;
import dev.forgeide.core.run.StepSnapshot;
import dev.forgeide.core.run.StepStatus;
import dev.forgeide.runtime.git.GitScopeDiff;
import dev.forgeide.runtime.state.RunExporter;
import dev.forgeide.ui.canvas.CanvasView;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Run view (SD §7, T10): the same canvas as T05 with a live status overlay, plus a bottom panel
 * with the timeline, the selected step's log, and this feature's run history. Owns the {@link
 * RunViewModel} (and therefore the FX-side subscription to {@link PipelineEngine}) for its own
 * lifetime — {@link #dispose()} must be called when navigating away.
 */
public final class RunView extends BorderPane {

    private final RunViewModel viewModel;
    private final StepLogView stepLogView;
    private final CanvasView canvas;
    private final PipelineDefinition pipeline;
    private final RunExporter exporter = new RunExporter();
    private final GitScopeDiff scopeDiff = new GitScopeDiff();
    private final ProjectDefinition project;
    private final StateStore stateStore;
    private final RunId runId;

    private StepDefinition selectedStep;
    private int lastTargetIteration = -1;

    /** Step id -> its latest unanswered gate/escalation request (T12: a dismissed dialog leaves
     * the step {@code WAITING_GATE} — this is what a canvas re-selection reopens). */
    private final Map<String, EngineEvent.GateRequest> pendingGates = new LinkedHashMap<>();
    private final Map<String, Stage> openGateDialogs = new LinkedHashMap<>();

    /** Step id -> its latest unanswered {@code pending_questions} round (FR-10.3: same
     * dismiss-and-reopen-from-canvas story as a gate, for a step left {@code WAITING_INPUT}). */
    private final Map<String, EngineEvent.QuestionsPending> pendingQuestions = new LinkedHashMap<>();
    private final Map<String, Stage> openQuestionDialogs = new LinkedHashMap<>();

    public RunView(PipelineEngine engine, StateStore stateStore, ProjectDefinition project,
                   PipelineDefinition pipeline, RunId runId, String featureSlug, Runnable onBack) {
        this.pipeline = pipeline;
        this.project = project;
        this.stateStore = stateStore;
        this.runId = runId;
        this.viewModel = new RunViewModel(engine, runId);

        canvas = new CanvasView(pipeline, List.<PipelineError>of(), TileValidityChecker.unknown());
        stepLogView = new StepLogView(project.repositoryPath(), featureSlug);
        TimelineView timelineView = new TimelineView(stateStore, runId, this::showLog);
        timelineView.bindTo(viewModel.snapshotProperty());
        RunListView historyView = new RunListView(stateStore, featureSlug);

        TabPane bottom = new TabPane(
                new Tab("Timeline", timelineView),
                new Tab("Step log", stepLogView),
                new Tab("Run history", historyView));
        bottom.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        SplitPane split = new SplitPane(canvas, bottom);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.6);
        setCenter(split);

        Button back = new Button("← Back");
        Button cancel = new Button("Cancel run");
        Button retry = new Button("Retry step");
        Button rollback = new Button("Roll back extra changes");
        Button export = new Button("Export…");
        Label status = new Label();
        back.setOnAction(e -> {
            dispose();
            onBack.run();
        });
        cancel.setOnAction(e -> viewModel.cancel());
        retry.setOnAction(e -> {
            if (selectedStep != null) {
                viewModel.retry(selectedStep.id());
            }
        });
        retry.setDisable(true);
        rollback.setOnAction(e -> rollbackScope());
        rollback.setDisable(true);
        export.setOnAction(e -> exportRun());
        HBox header = new HBox(12, back, new Label(project.name() + " · " + featureSlug), cancel, retry, rollback,
                export, status);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(12));
        setTop(header);

        canvas.selectedStepProperty().addListener((obs, old, step) -> {
            selectedStep = step;
            lastTargetIteration = -1;
            retargetLog();
            retry.setDisable(!isSelectedStepFailed());
            rollback.setDisable(!isSelectedStepScopeViolation());
            if (step != null && pendingGates.containsKey(step.id())) {
                openGateDialog(pendingGates.get(step.id()));
            }
            if (step != null && pendingQuestions.containsKey(step.id())) {
                openQuestionDialog(pendingQuestions.get(step.id()));
            }
        });

        viewModel.snapshotProperty().addListener((obs, old, snapshot) -> {
            if (snapshot == null) {
                return;
            }
            canvas.applyRunSnapshot(snapshot);
            status.setText(snapshot.status().name() + snapshot.haltReason().map(r -> " (" + r + ")").orElse(""));
            boolean terminal = snapshot.status() == RunStatus.COMPLETED || snapshot.status() == RunStatus.CANCELLED
                    || snapshot.status() == RunStatus.STOPPED;
            cancel.setDisable(terminal);
            retry.setDisable(!isSelectedStepFailed());
            rollback.setDisable(!isSelectedStepScopeViolation());
            retargetLog();
            prunePendingGates(snapshot);
            prunePendingQuestions(snapshot);
        });
        Optional.ofNullable(viewModel.snapshotProperty().get()).ifPresent(canvas::applyRunSnapshot);

        viewModel.onGateRequest(this::onGateRequest);
        viewModel.onQuestionsPending(this::onQuestionsPending);
    }

    /** T12: opens (or focuses an already-open) dialog the moment the engine asks; the same
     * request is kept so a later canvas click can reopen it if the human dismissed it. */
    private void onGateRequest(EngineEvent.GateRequest request) {
        pendingGates.put(request.stepId(), request);
        openGateDialog(request);
    }

    private void openGateDialog(EngineEvent.GateRequest request) {
        Stage existing = openGateDialogs.get(request.stepId());
        if (existing != null) {
            existing.toFront();
            existing.requestFocus();
            return;
        }
        Stage stage = GateDialog.show(request, project.repositoryPath(),
                (answer, detail, diffAcked) -> {
                    viewModel.answerGate(request.stepId(), answer, detail, diffAcked);
                    pendingGates.remove(request.stepId());
                    openGateDialogs.remove(request.stepId());
                },
                () -> openGateDialogs.remove(request.stepId()));
        openGateDialogs.put(request.stepId(), stage);
    }

    /** A step that left {@code WAITING_GATE} (answered from this or another IDE instance, or
     * cancelled) no longer needs a reopenable pending request. */
    private void prunePendingGates(RunSnapshot snapshot) {
        pendingGates.keySet().removeIf(stepId -> snapshot.steps().stream()
                .filter(s -> s.stepId().equals(stepId))
                .findFirst()
                .map(s -> s.status() != StepStatus.WAITING_GATE)
                .orElse(true));
    }

    /** FR-10.3: opens (or focuses an already-open) dialog the moment the engine asks; the same
     * request is kept so a later canvas click can reopen it if the human dismissed it. */
    private void onQuestionsPending(EngineEvent.QuestionsPending request) {
        pendingQuestions.put(request.stepId(), request);
        openQuestionDialog(request);
    }

    private void openQuestionDialog(EngineEvent.QuestionsPending request) {
        Stage existing = openQuestionDialogs.get(request.stepId());
        if (existing != null) {
            existing.toFront();
            existing.requestFocus();
            return;
        }
        Stage stage = QuestionDialog.show(request, project.repositoryPath(),
                answers -> {
                    viewModel.answerQuestions(request.stepId(), answers);
                    pendingQuestions.remove(request.stepId());
                    openQuestionDialogs.remove(request.stepId());
                },
                () -> openQuestionDialogs.remove(request.stepId()));
        openQuestionDialogs.put(request.stepId(), stage);
    }

    /** A step that left {@code WAITING_INPUT} (answered, escalated, or cancelled) no longer
     * needs a reopenable pending question round. */
    private void prunePendingQuestions(RunSnapshot snapshot) {
        pendingQuestions.keySet().removeIf(stepId -> snapshot.steps().stream()
                .filter(s -> s.stepId().equals(stepId))
                .findFirst()
                .map(s -> s.status() != StepStatus.WAITING_INPUT)
                .orElse(true));
    }

    /** {@code Retry step} is only ever enabled for the selected tile's own current status — the
     * engine still has the last word (a FAILED step blocked on an incident, T11 scope, is a no-op
     * even if this fires). */
    private boolean isSelectedStepFailed() {
        if (selectedStep == null) {
            return false;
        }
        RunSnapshot snapshot = viewModel.snapshotProperty().get();
        if (snapshot == null) {
            return false;
        }
        return snapshot.steps().stream()
                .filter(s -> s.stepId().equals(selectedStep.id()))
                .findFirst()
                .map(s -> s.status() == StepStatus.FAILED)
                .orElse(false);
    }

    /** {@code Roll back extra changes} (T16 scope, SR-6/Т-13's incident-dialog affordance) is
     * only enabled for a step FAILED specifically with {@code SCOPE} — the one failure reason
     * that actually names files worth reverting. */
    private boolean isSelectedStepScopeViolation() {
        if (selectedStep == null) {
            return false;
        }
        RunSnapshot snapshot = viewModel.snapshotProperty().get();
        if (snapshot == null) {
            return false;
        }
        return snapshot.steps().stream()
                .filter(s -> s.stepId().equals(selectedStep.id()))
                .findFirst()
                .map(s -> s.status() == StepStatus.FAILED && s.failureReason().filter(r -> r == FailureReason.SCOPE).isPresent())
                .orElse(false);
    }

    /** Reverts strictly the files {@code incident.scope}'s own audit detail named for the
     * selected step (SR-6) — never a broader {@code git clean}/{@code reset}, and never without
     * the human confirming the exact list first. */
    private void rollbackScope() {
        if (selectedStep == null) {
            return;
        }
        List<String> violations = ScopeIncident.violatingPaths(stateStore.loadAudit(runId), selectedStep.id());
        if (violations.isEmpty()) {
            info("Nothing to roll back", "No scope-violation file list recorded for this step.");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Revert exactly these file(s) to their state before this phase started?\n\n"
                        + String.join("\n", violations));
        confirm.setHeaderText("Roll back " + violations.size() + " file(s) outside allowed_write");
        confirm.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b -> {
            List<String> restored = scopeDiff.rollback(project.repositoryPath(), violations);
            info("Rollback complete", "Restored " + restored.size() + " of " + violations.size() + " file(s).");
        });
    }

    /** Called from the Timeline's "jump to log" action. */
    private void showLog(String stepId, int iteration) {
        try {
            StepDefinition def = pipeline.step(stepId);
            selectedStep = def;
            lastTargetIteration = -1;
            stepLogView.setTarget(def, iteration);
            lastTargetIteration = iteration;
        } catch (NoSuchElementException notInStaticDefinition) {
            // A per_task_loop-unrolled step instance has no entry in the static PipelineDefinition
            // this view was built from — known gap, see the T10 plan's limitations.
        }
    }

    private void retargetLog() {
        if (selectedStep == null) {
            return;
        }
        RunSnapshot snapshot = viewModel.snapshotProperty().get();
        int iteration = snapshot == null ? 0 : snapshot.steps().stream()
                .filter(s -> s.stepId().equals(selectedStep.id()))
                .findFirst()
                .map(StepSnapshot::iteration)
                .orElse(0);
        if (iteration != lastTargetIteration) {
            stepLogView.setTarget(selectedStep, iteration);
            lastTargetIteration = iteration;
        }
    }

    private void exportRun() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export run");
        chooser.setInitialFileName("run-" + runId.value() + ".zip");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Zip archive", "*.zip"));
        Window window = getScene() == null ? null : getScene().getWindow();
        java.io.File target = chooser.showSaveDialog(window);
        if (target == null) {
            return;
        }
        Thread.ofVirtual().start(() -> {
            try {
                RunSnapshot snapshot = stateStore.load(runId).orElseThrow();
                List<AuditEvent> audit = stateStore.loadAudit(runId);
                boolean staleWarning = stateStore.listRuns(snapshot.featureSlug()).size() > 1;
                exporter.exportRun(snapshot, audit, project.repositoryPath(), target.toPath(), staleWarning);
                Platform.runLater(() -> info("Export complete", "Wrote " + target));
            } catch (Exception ex) {
                Platform.runLater(() -> info("Export failed", String.valueOf(ex.getMessage())));
            }
        });
    }

    private void info(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message);
        alert.setHeaderText(title);
        alert.showAndWait();
    }

    /** Stops the live subscription and any log tailers; call before navigating away. */
    public void dispose() {
        viewModel.dispose();
        stepLogView.dispose();
        List.copyOf(openGateDialogs.values()).forEach(Stage::close);
        openGateDialogs.clear();
        List.copyOf(openQuestionDialogs.values()).forEach(Stage::close);
        openQuestionDialogs.clear();
    }
}
