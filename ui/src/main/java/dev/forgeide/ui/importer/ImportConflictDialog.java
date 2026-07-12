package dev.forgeide.ui.importer;

import dev.forgeide.importer.ImportWriter;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * T35's re-import conflict prompt: a small modal (same own-{@link Stage} shape as {@link
 * SectionPickerDialog}) listing every {@link ImportWriter.FileDiff} the pending import would
 * overwrite with different content — locally edited prompts (T20 inspector) or judge scripts most
 * often (SD §8, ревью импортёра 2026-07-11 №4) — and asking the user to either overwrite all of
 * them (backed up first under {@code .forgeide/import-backup/<timestamp>/}) or leave every one of
 * them alone ("skip conflicting"). Closing the dialog any other way writes nothing — {@code
 * onConfirm} only fires from an explicit choice.
 */
public final class ImportConflictDialog {

    private ImportConflictDialog() {
    }

    public static void show(List<ImportWriter.FileDiff> conflicts, Consumer<Set<Path>> onConfirm) {
        Stage stage = new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Конфликты при повторном импорте");

        ListView<ImportWriter.FileDiff> list = new ListView<>(FXCollections.observableArrayList(conflicts));
        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(ImportWriter.FileDiff diff, boolean empty) {
                super.updateItem(diff, empty);
                setText(empty || diff == null ? null : ImportConflictPresentation.rowText(diff));
            }
        });

        Label message = new Label(ImportConflictPresentation.summary(conflicts)
                + " — эти файлы правились локально после прошлого импорта. Перезаписать их "
                + "(прежняя версия сохранится в .forgeide/import-backup/) или оставить как есть?");
        message.setWrapText(true);

        Button overwriteAll = new Button("Перезаписать все");
        overwriteAll.setOnAction(e -> {
            onConfirm.accept(conflicts.stream()
                    .map(ImportWriter.FileDiff::relativePath)
                    .collect(Collectors.toUnmodifiableSet()));
            stage.close();
        });
        Button skip = new Button("Пропустить конфликтующие");
        skip.setOnAction(e -> {
            onConfirm.accept(Set.of());
            stage.close();
        });
        Button cancel = new Button("Отмена");
        cancel.setOnAction(e -> stage.close());

        HBox buttons = new HBox(8, overwriteAll, skip, cancel);

        VBox root = new VBox(10, message, list, buttons);
        root.setPadding(new Insets(12));
        VBox.setVgrow(list, Priority.ALWAYS);

        stage.setScene(new Scene(root, 560, 420));
        stage.show();
    }
}
