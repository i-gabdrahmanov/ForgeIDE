package dev.forgeide.ui.run;

import dev.forgeide.core.audit.AuditEvent;
import dev.forgeide.core.port.StateStore;
import dev.forgeide.core.run.RunId;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * Run timeline (SDD FR-7.6): audit hash-chain, filtered by step / event type / "only incidents",
 * click → payload + a "jump to log" shortcut. Reloads off the FX thread — {@code
 * StateStore#loadAudit} re-verifies the whole hash chain, so it must not run synchronously on
 * every {@code RunUpdated} tick; bursts of updates are coalesced rather than queued.
 */
public final class TimelineView extends BorderPane {

    private static final String ALL = "All";

    private final StateStore stateStore;
    private final RunId runId;
    private final BiConsumer<String, Integer> onJumpToStep;

    private final ObservableList<TimelineRow> allRows = FXCollections.observableArrayList();
    private final ObservableList<TimelineRow> visibleRows = FXCollections.observableArrayList();
    private final ComboBox<String> stepFilter = new ComboBox<>();
    private final ComboBox<String> typeFilter = new ComboBox<>();
    private final CheckBox onlyIncidents = new CheckBox("Only incidents");
    private final TextArea payloadView = new TextArea();

    private final AtomicBoolean loading = new AtomicBoolean(false);
    private final AtomicBoolean reloadPending = new AtomicBoolean(false);

    public TimelineView(StateStore stateStore, RunId runId, BiConsumer<String, Integer> onJumpToStep) {
        this.stateStore = stateStore;
        this.runId = runId;
        this.onJumpToStep = onJumpToStep;

        stepFilter.getItems().add(ALL);
        stepFilter.setValue(ALL);
        typeFilter.getItems().add(ALL);
        typeFilter.setValue(ALL);
        stepFilter.setOnAction(e -> applyFilters());
        typeFilter.setOnAction(e -> applyFilters());
        onlyIncidents.setOnAction(e -> applyFilters());

        HBox filters = new HBox(8, new Label("Step:"), stepFilter, new Label("Type:"), typeFilter, onlyIncidents);
        filters.setAlignment(Pos.CENTER_LEFT);
        filters.setPadding(new Insets(6));

        ListView<TimelineRow> list = new ListView<>(visibleRows);
        list.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(TimelineRow row, boolean empty) {
                super.updateItem(row, empty);
                if (empty || row == null) {
                    setText(null);
                    setStyle("");
                    return;
                }
                String step = row.stepId() == null ? "-" : row.stepId();
                setText(row.seq() + "  " + DateTimeFormatter.ISO_INSTANT.format(row.ts())
                        + "  " + step + "  " + row.type());
                setStyle(row.incident() ? "-fx-text-fill: #d93025; -fx-font-weight: bold;" : "");
            }
        });

        Button jumpToLog = new Button("Jump to log");
        jumpToLog.setDisable(true);
        list.getSelectionModel().selectedItemProperty().addListener((obs, old, row) -> {
            if (row == null) {
                payloadView.clear();
                jumpToLog.setDisable(true);
                return;
            }
            payloadView.setText(row.payload().toPrettyString());
            jumpToLog.setDisable(row.stepId() == null);
        });
        jumpToLog.setOnAction(e -> {
            TimelineRow row = list.getSelectionModel().getSelectedItem();
            if (row != null && row.stepId() != null) {
                onJumpToStep.accept(row.stepId(), row.iteration());
            }
        });

        payloadView.setEditable(false);
        payloadView.setPrefRowCount(6);
        VBox detail = new VBox(4, new Label("Payload"), payloadView, jumpToLog);
        detail.setPadding(new Insets(6));

        VBox center = new VBox(4, filters, list, detail);
        VBox.setVgrow(list, Priority.ALWAYS);
        setCenter(center);
    }

    /** Call whenever the run's snapshot changes (e.g. from {@link RunViewModel#snapshotProperty()}). */
    public void bindTo(ObjectProperty<?> snapshotProperty) {
        snapshotProperty.addListener((obs, old, updated) -> scheduleReload());
        scheduleReload();
    }

    private void scheduleReload() {
        if (!loading.compareAndSet(false, true)) {
            reloadPending.set(true);
            return;
        }
        Thread.ofVirtual().start(this::reloadThenMaybeAgain);
    }

    private void reloadThenMaybeAgain() {
        List<AuditEvent> events = stateStore.loadAudit(runId);
        List<TimelineRow> rows = events.stream().map(TimelineRow::of).toList();
        Platform.runLater(() -> setRows(rows));
        loading.set(false);
        if (reloadPending.compareAndSet(true, false)) {
            scheduleReload();
        }
    }

    private void setRows(List<TimelineRow> rows) {
        allRows.setAll(rows);
        List<String> steps = rows.stream().map(TimelineRow::stepId).filter(java.util.Objects::nonNull).distinct().sorted().toList();
        List<String> types = rows.stream().map(TimelineRow::type).distinct().sorted().toList();
        mergeItems(stepFilter, steps);
        mergeItems(typeFilter, types);
        applyFilters();
    }

    private static void mergeItems(ComboBox<String> box, List<String> values) {
        String current = box.getValue();
        List<String> items = new java.util.ArrayList<>();
        items.add(ALL);
        items.addAll(values);
        box.getItems().setAll(items);
        box.setValue(items.contains(current) ? current : ALL);
    }

    private void applyFilters() {
        Optional<String> step = ALL.equals(stepFilter.getValue()) ? Optional.empty() : Optional.ofNullable(stepFilter.getValue());
        Optional<String> type = ALL.equals(typeFilter.getValue()) ? Optional.empty() : Optional.ofNullable(typeFilter.getValue());
        visibleRows.setAll(TimelineFilter.apply(allRows, step, type, onlyIncidents.isSelected()));
    }
}
