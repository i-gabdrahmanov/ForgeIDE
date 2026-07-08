package dev.forgeide.ui.run;

import dev.forgeide.core.port.StateStore;
import dev.forgeide.core.run.RunId;
import dev.forgeide.core.run.RunSnapshot;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Optional;

/**
 * Feature run-history list (SDD FR-7.9): every run of one {@code featureSlug} with its status
 * and per-step summary. Read-only — comparing two runs is explicitly post-MVP (T10 scope), and
 * token totals are dropped for this task (see the T10 plan's known limitations: {@code
 * ground/ai-logs/} isn't runId-scoped, so a historical total could be silently wrong).
 */
public final class RunListView extends BorderPane {

    public RunListView(StateStore stateStore, String featureSlug) {
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
}
