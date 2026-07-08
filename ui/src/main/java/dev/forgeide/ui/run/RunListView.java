package dev.forgeide.ui.run;

import dev.forgeide.core.port.StateStore;
import dev.forgeide.core.run.RunId;
import dev.forgeide.core.run.RunSnapshot;
import dev.forgeide.core.run.RunStatus;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Feature run-history list (SDD FR-7.9): every run of one {@code featureSlug} with its status
 * and per-step summary. Read-only aside from resuming a still-{@code RUNNING} run's engine (T11:
 * "предложение ретрая в UI" for a run FR-3.4 recovery left with an interrupted step) — comparing
 * two runs is still explicitly post-MVP (T10 scope), and token totals are still dropped (see the
 * T10 plan's known limitations: {@code ground/ai-logs/} isn't runId-scoped).
 */
public final class RunListView extends BorderPane {

    public RunListView(StateStore stateStore, String featureSlug) {
        this(stateStore, featureSlug, Optional.empty());
    }

    /** @param onResume present to offer a "Resume" action on any non-terminal run (T11); empty for
     *                  the read-only usage embedded inside an already-live {@link RunView}. */
    public RunListView(StateStore stateStore, String featureSlug, Optional<Consumer<RunSnapshot>> onResume) {
        ListView<RunSnapshot> list = new ListView<>();
        list.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(RunSnapshot snapshot, boolean empty) {
                super.updateItem(snapshot, empty);
                if (empty || snapshot == null) {
                    setText(null);
                    return;
                }
                RunListSummary.Summary s = RunListSummary.summarize(snapshot);
                setText(snapshot.runId().value() + "  " + s.status() + "  steps " + s.passedSteps() + "/"
                        + s.totalSteps() + " passed" + (s.failedSteps() > 0 ? ", " + s.failedSteps() + " failed" : "")
                        + "  (" + s.totalIterations() + " iteration(s) total)");
            }
        });

        Label loading = new Label("Loading run history for '" + featureSlug + "'…");
        VBox loadingBox = new VBox(8, loading);
        loadingBox.setPadding(new Insets(12));
        setCenter(loadingBox);

        onResume.ifPresent(resume -> {
            Button resumeButton = new Button("Resume selected run");
            resumeButton.setDisable(true);
            list.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) ->
                    resumeButton.setDisable(selected == null || isTerminal(selected.status())));
            resumeButton.setOnAction(e -> {
                RunSnapshot selected = list.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    resume.accept(selected);
                }
            });
            HBox actions = new HBox(8, resumeButton);
            actions.setAlignment(Pos.CENTER_LEFT);
            actions.setPadding(new Insets(8));
            setBottom(actions);
        });

        Thread.ofVirtual().start(() -> {
            List<RunId> ids = stateStore.listRuns(featureSlug);
            List<RunSnapshot> snapshots = ids.stream()
                    .map(stateStore::load)
                    .flatMap(Optional::stream)
                    .toList();
            Platform.runLater(() -> {
                list.getItems().setAll(snapshots);
                setCenter(snapshots.isEmpty() ? new Label("No runs recorded yet for '" + featureSlug + "'.") : list);
            });
        });
    }

    private static boolean isTerminal(RunStatus status) {
        return status == RunStatus.COMPLETED || status == RunStatus.CANCELLED || status == RunStatus.STOPPED;
    }
}
