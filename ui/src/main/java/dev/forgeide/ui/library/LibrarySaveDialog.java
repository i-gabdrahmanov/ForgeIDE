package dev.forgeide.ui.library;

import dev.forgeide.core.pipeline.library.LibraryScope;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * T23/FR-2.9 "save to library" form — a small modal window (same convention as {@code
 * GateDialog}/{@code QuestionDialog}: its own {@link Stage} rather than a full screen swap, since
 * it is a quick side-action off the canvas toolbar, not navigation). Collects exactly the
 * registry fields FR-2.9 asks for (owner/validity/scope) plus which of the two libraries to save
 * into; the caller (the one place that actually knows the current selection and how to read
 * prompt/script files off disk) does the rest.
 */
public final class LibrarySaveDialog {

    public record Request(String title, String owner, List<String> scopeTags,
                           Optional<LocalDate> validUntil, LibraryScope libraryScope) {
    }

    @FunctionalInterface
    public interface OnSave {
        void save(Request request);
    }

    private LibrarySaveDialog() {
    }

    public static void show(int selectedStepCount, OnSave onSave) {
        Stage stage = new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Save to library");

        Label info = new Label(selectedStepCount + (selectedStepCount == 1 ? " step selected" : " steps selected"));

        TextField titleField = new TextField();
        titleField.setPromptText("e.g. \"TDD green + coverage judge\"");
        TextField ownerField = new TextField();
        ownerField.setPromptText("who to ask about this tile");
        TextField scopeField = new TextField();
        scopeField.setPromptText("comma-separated tags, e.g. agent, judge, gate");
        DatePicker validUntil = new DatePicker();

        ToggleGroup group = new ToggleGroup();
        RadioButton projectScope = new RadioButton("Project library (.forgeide/library, shareable via git)");
        RadioButton userScope = new RadioButton("User library (~/.forgeide/library, this machine)");
        projectScope.setToggleGroup(group);
        userScope.setToggleGroup(group);
        projectScope.setSelected(true);

        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(8);
        form.addRow(0, new Label("Title"), titleField);
        form.addRow(1, new Label("Owner"), ownerField);
        form.addRow(2, new Label("Scope tags"), scopeField);
        form.addRow(3, new Label("Valid until"), validUntil);

        Label error = new Label();
        error.setStyle("-fx-text-fill: #d93025;");

        Button save = new Button("Save");
        Button cancel = new Button("Cancel");
        cancel.setOnAction(e -> stage.close());
        save.setOnAction(e -> {
            if (titleField.getText() == null || titleField.getText().isBlank()) {
                error.setText("Title is required.");
                return;
            }
            LibraryScope scope = projectScope.isSelected() ? LibraryScope.PROJECT : LibraryScope.USER;
            onSave.save(new Request(titleField.getText().trim(),
                    ownerField.getText() == null ? "" : ownerField.getText().trim(),
                    splitTags(scopeField.getText()), Optional.ofNullable(validUntil.getValue()), scope));
            stage.close();
        });

        VBox root = new VBox(12, info, form, new VBox(4, projectScope, userScope), error, new HBox(8, save, cancel));
        root.setPadding(new Insets(16));

        stage.setScene(new Scene(root, 460, 340));
        stage.show();
    }

    private static List<String> splitTags(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return Arrays.stream(text.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList();
    }
}
