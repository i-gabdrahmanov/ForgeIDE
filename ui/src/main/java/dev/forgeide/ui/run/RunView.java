package dev.forgeide.ui.run;

import dev.forgeide.core.audit.AuditEvent;
import dev.forgeide.core.engine.PipelineEngine;
import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.StepDefinition;
import dev.forgeide.core.pipeline.validation.PipelineError;
import dev.forgeide.core.port.StateStore;
import dev.forgeide.core.port.TileValidityChecker;
import dev.forgeide.core.project.ProjectDefinition;
import dev.forgeide.core.run.RunId;
import dev.forgeide.core.run.RunSnapshot;
import dev.forgeide.core.run.RunStatus;
import dev.forgeide.core.run.StepSnapshot;
import dev.forgeide.core.run.StepStatus;
import dev.forgeide.runtime.state.RunExporter;
import dev.forgeide.ui.canvas.CanvasView;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.util.List;
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
    private final ProjectDefinition project;
    private final StateStore stateStore;
    private final RunId runId;

    private StepDefinition selectedStep;
    private int lastTargetIteration = -1;

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
        export.setOnAction(e -> exportRun());
        HBox header = new HBox(12, back, new Label(project.name() + " · " + featureSlug), cancel, retry, export, status);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(12));
        setTop(header);

        canvas.selectedStepProperty().addListener((obs, old, step) -> {
            selectedStep = step;
            lastTargetIteration = -1;
            retargetLog();
            retry.setDisable(!isSelectedStepFailed());
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
            retargetLog();
        });
        Optional.ofNullable(viewModel.snapshotProperty().get()).ifPresent(canvas::applyRunSnapshot);
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
    }
}
