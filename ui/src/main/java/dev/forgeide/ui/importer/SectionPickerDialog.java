package dev.forgeide.ui.importer;

import dev.forgeide.importer.scaffold.PromptSection;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.List;
import java.util.function.Consumer;

/**
 * T33's §-section picker: a small modal ({@code LibrarySaveDialog}'s own-{@link Stage} shape, not
 * a screen swap — this is a quick side-action off the import screen) that lists {@link
 * PromptSection} candidates by heading, previews the selected one's body, and hands the chosen
 * section back to the caller. Serves both cases the task adds on top of T24's {@code findFirst}
 * auto-match: an {@link dev.forgeide.importer.bind.TileBinding.Ambiguous} tile's candidates, and
 * the section list {@code ImportSession.sectionsOf} returns for a manually chosen markdown file.
 */
public final class SectionPickerDialog {

    private SectionPickerDialog() {
    }

    public static void show(String title, List<PromptSection> candidates, Consumer<PromptSection> onPick) {
        Stage stage = new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle(title);

        ListView<PromptSection> list = new ListView<>(FXCollections.observableArrayList(candidates));
        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(PromptSection section, boolean empty) {
                super.updateItem(section, empty);
                setText(empty || section == null ? null : section.heading());
            }
        });

        TextArea preview = new TextArea();
        preview.setEditable(false);
        preview.setStyle("-fx-font-family: monospace;");
        list.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) ->
                preview.setText(sel == null ? "" : sel.body()));
        list.getSelectionModel().selectFirst();

        Button pick = new Button("Выбрать");
        pick.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        Button cancel = new Button("Отмена");
        cancel.setOnAction(e -> stage.close());
        pick.setOnAction(e -> {
            PromptSection selected = list.getSelectionModel().getSelectedItem();
            if (selected == null) {
                return;
            }
            onPick.accept(selected);
            stage.close();
        });

        HBox lists = new HBox(8, list, preview);
        HBox.setHgrow(list, Priority.ALWAYS);
        HBox.setHgrow(preview, Priority.ALWAYS);

        VBox root = new VBox(10, new Label("Выберите §-секцию:"), lists, new HBox(8, pick, cancel));
        root.setPadding(new Insets(12));
        VBox.setVgrow(lists, Priority.ALWAYS);

        stage.setScene(new Scene(root, 640, 420));
        stage.show();
    }
}
