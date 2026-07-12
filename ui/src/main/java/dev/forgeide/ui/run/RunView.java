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
import dev.forgeide.core.run.QuestionEscalationAction;
import dev.forgeide.core.run.RunHaltReason;
import dev.forgeide.core.run.RunId;
import dev.forgeide.core.run.RunSnapshot;
import dev.forgeide.core.run.RunStatus;
import dev.forgeide.core.run.StepSnapshot;
import dev.forgeide.core.run.StepStatus;
import dev.forgeide.runtime.git.GitScopeDiff;
import dev.forgeide.runtime.state.RunExporter;
import dev.forgeide.ui.canvas.CanvasView;
import dev.forgeide.ui.editor.HarnessPaths;
import dev.forgeide.ui.editor.TileEditorPanel;
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
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final TileEditorPanel inspector;
    private final TabPane bottom;
    private final Tab inspectorTab;
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

    /** T36/SR-6: a persistent, non-blocking banner for "this run started on a dirty git tree, so
     * scope-diff is weakened for its whole duration" — see {@code RunLifecycle#warnIfTreeDirty}.
     * {@code dirtyTreeChecked} guards against re-loading the audit chain on every snapshot tick
     * once the answer (found or genuinely clean) is known. */
    private final Label dirtyTreeBanner = new Label(
            "⚠ Run started on a dirty git tree — scope-diff is weakened for files already dirty "
                    + "at start (see docs/manual.md §11). Recommendation: run on a clean tree.");
    private final AtomicBoolean dirtyTreeCheckInFlight = new AtomicBoolean(false);
    private volatile boolean dirtyTreeChecked = false;

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
        // T20/FR-8.2-8.3: mid-run saves always route through the live engine (never a direct
        // write) so a prompt edit gets the "next dispatch only" + audit treatment, and a harness
        // script edit becomes the new trusted baseline instead of registering as drift. A script
        // outside the harness has no such determinism/drift concern (never snapshotted by the
        // engine, read fresh off disk by ScriptExecutor every time) so it writes straight through.
        // T21/FR-8.4-8.5: same "always through the live engine" reasoning as the T20 save
        // handlers above — a dry-run needs the engine's own resolver/cache-guard/script-runner
        // wiring, and a prompt preview must render through the engine's own dispatch-render code
        // (never a UI-side reimplementation) to stay byte-identical to what a real run sends.
        inspector = new TileEditorPanel(project.repositoryPath(),
                (stepId, absolutePath, content) -> viewModel.editPrompt(stepId, content),
                (relativePath, content) -> {
                    if (HarnessPaths.isUnderHarness(relativePath)) {
                        viewModel.editHarness(HarnessPaths.harnessRelative(relativePath), content);
                    } else {
                        writeDirect(project.repositoryPath().resolve(relativePath), content);
                    }
                },
                viewModel::requestPromptPreview,
                (judgeStepId, onResult) -> viewModel.requestJudgeDryRun(judgeStepId,
                        result -> onResult.accept(new TileEditorPanel.JudgeDryRunOutcome(result.passed(), result.detail()))));

        inspectorTab = new Tab("Inspector", inspector);
        bottom = new TabPane(
                new Tab("Timeline", timelineView),
                new Tab("Step log", stepLogView),
                inspectorTab,
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
        Runnable goBack = () -> {
            dispose();
            onBack.run();
        };
        back.setOnAction(e -> goBack.run());
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
        dirtyTreeBanner.setStyle("-fx-text-fill: #a15c00; -fx-font-weight: bold;");
        dirtyTreeBanner.setPadding(new Insets(0, 12, 8, 12));
        dirtyTreeBanner.setVisible(false);
        dirtyTreeBanner.setManaged(false);

        // T37/FR-1.4: a run that never even started because the harness wasn't deployed (or its
        // last preflight didn't pass) needs to point the human at the fix, not just print the
        // bare enum name next to "PAUSED" — see the acceptance in T37's task doc: "прогон на
        // HARNESS_PREFLIGHT ведёт пользователя к деплою". Synchronous (no audit-chain load): the
        // halt reason is already on every published snapshot, and this — unlike a mid-run pause —
        // can never toggle back off within the same run.
        Label harnessPreflightMessage = new Label(
                "⚠ Run paused — the harness hasn't been deployed, or its last preflight didn't pass. "
                        + "Deploy the harness on the project card, then start a new run.");
        harnessPreflightMessage.setStyle("-fx-text-fill: #a15c00; -fx-font-weight: bold;");
        Button goToProjectCard = new Button("Go to project card");
        goToProjectCard.setOnAction(e -> goBack.run());
        HBox harnessPreflightBanner = new HBox(12, harnessPreflightMessage, goToProjectCard);
        harnessPreflightBanner.setAlignment(Pos.CENTER_LEFT);
        harnessPreflightBanner.setPadding(new Insets(0, 12, 8, 12));
        harnessPreflightBanner.setVisible(false);
        harnessPreflightBanner.setManaged(false);

        setTop(new VBox(header, dirtyTreeBanner, harnessPreflightBanner));

        canvas.selectedStepProperty().addListener((obs, old, step) -> {
            selectedStep = step;
            lastTargetIteration = -1;
            retargetLog();
            retry.setDisable(!isSelectedStepFailed());
            rollback.setDisable(!isSelectedStepScopeViolation());
            if (step == null) {
                inspector.showEmpty();
            } else {
                inspector.show(step, List.of(), TileValidityChecker.unknown().check(step));
            }
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
            maybeCheckDirtyTreeWarning(snapshot);
            applyHarnessPreflightBanner(harnessPreflightBanner, snapshot);
        });
        Optional.ofNullable(viewModel.snapshotProperty().get()).ifPresent(snapshot -> {
            canvas.applyRunSnapshot(snapshot);
            maybeCheckDirtyTreeWarning(snapshot);
            applyHarnessPreflightBanner(harnessPreflightBanner, snapshot);
        });

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
                () -> openGateDialogs.remove(request.stepId()),
                () -> openPromptEditor(request.stepId()),
                () -> retryQuestionEscalation(request.stepId()));
        openGateDialogs.put(request.stepId(), stage);
    }

    /** T25/FR-10.5: the question-escalation's "open prompt" link dismisses the dialog like any
     * other deferred gate answer and lands here instead — shown directly on the existing {@code
     * inspector}, not via {@code canvas.selectedStepProperty()}, because that listener would
     * immediately reopen the very dialog we just dismissed (the step is still {@code
     * WAITING_GATE} and still in {@code pendingGates}). */
    private void openPromptEditor(String stepId) {
        try {
            StepDefinition def = pipeline.step(stepId);
            selectedStep = def;
            inspector.show(def, List.of(), TileValidityChecker.unknown().check(def));
            bottom.getSelectionModel().select(inspectorTab);
        } catch (NoSuchElementException notInStaticDefinition) {
            // A per_task_loop-unrolled step instance has no entry in the static PipelineDefinition
            // this view was built from — same known gap as showLog().
        }
    }

    /** T25's "Повторить с новым промптом": composes the existing {@code cancel} gate answer (ends
     * this escalated attempt as {@code FAILED(questions)}) with the existing manual-retry command
     * — both already submitted to the same single-threaded engine mailbox, so the retry is always
     * processed after the cancel lands. No new engine command. */
    private void retryQuestionEscalation(String stepId) {
        viewModel.answerGate(stepId, QuestionEscalationAction.CANCEL.token(), Optional.empty(), false);
        viewModel.retry(stepId);
        pendingGates.remove(stepId);
        openGateDialogs.remove(stepId);
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

    /** T37: toggles the "deploy the harness" banner for exactly {@code PAUSED(HARNESS_PREFLIGHT)}. */
    private static void applyHarnessPreflightBanner(HBox banner, RunSnapshot snapshot) {
        boolean blocked = snapshot.status() == RunStatus.PAUSED
                && snapshot.haltReason().filter(r -> r == RunHaltReason.HARNESS_PREFLIGHT).isPresent();
        banner.setVisible(blocked);
        banner.setManaged(blocked);
    }

    /**
     * T36: {@code run.dirty_tree} is written (if at all) strictly before {@link RunSnapshot}'s
     * every step leaves {@code PENDING} — both happen on the actor thread, in that order, inside
     * the same {@code RunLifecycle#bootstrap} call — so waiting for the first non-{@code PENDING}
     * step before loading the audit chain is race-free without polling on every snapshot tick:
     * once resolved once (found or genuinely clean), this never runs again for this view.
     */
    private void maybeCheckDirtyTreeWarning(RunSnapshot snapshot) {
        if (dirtyTreeChecked || snapshot.steps().stream().allMatch(s -> s.status() == StepStatus.PENDING)) {
            return;
        }
        if (!dirtyTreeCheckInFlight.compareAndSet(false, true)) {
            return;
        }
        Thread.ofVirtual().start(() -> {
            boolean dirty = stateStore.loadAudit(runId).stream().anyMatch(e -> e.type().equals("run.dirty_tree"));
            Platform.runLater(() -> {
                dirtyTreeChecked = true;
                if (dirty) {
                    dirtyTreeBanner.setVisible(true);
                    dirtyTreeBanner.setManaged(true);
                }
            });
        });
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

    private static void writeDirect(Path absolute, String content) {
        try {
            if (absolute.getParent() != null) {
                Files.createDirectories(absolute.getParent());
            }
            Files.writeString(absolute, content);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write " + absolute, e);
        }
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
