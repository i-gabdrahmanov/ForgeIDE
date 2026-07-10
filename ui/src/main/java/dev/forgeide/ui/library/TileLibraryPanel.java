package dev.forgeide.ui.library;

import dev.forgeide.core.pipeline.library.LibraryScope;
import dev.forgeide.core.pipeline.library.LibraryTile;
import dev.forgeide.core.pipeline.library.LibraryTileMetadata;
import dev.forgeide.core.pipeline.library.TileLibraryStore;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.function.Consumer;

/**
 * T23/FR-2.9 library browser: lists the project ({@code <project>/.forgeide/library/}) and user
 * ({@code ~/.forgeide/library/}) libraries side by side (one at a time, radio-switched), and lets
 * the caller insert a selected entry into the pipeline currently open on the canvas. Saving into
 * the library happens from the canvas toolbar ({@code ConstructorCanvasView.LibrarySaveHandler})
 * — this panel is the read/insert/delete half.
 */
public final class TileLibraryPanel extends BorderPane {

    private final TileLibraryStore store = new TileLibraryStore();
    private final Path projectRoot;
    private final Consumer<LibraryTile> onInsert;
    private final ListView<LibraryTileMetadata> list = new ListView<>();
    private final RadioButton projectScope = new RadioButton("Project library");
    private final RadioButton userScope = new RadioButton("User library");
    private final Button insertButton = new Button("Insert into pipeline");
    private final Button deleteButton = new Button("Delete");

    public TileLibraryPanel(Path projectRoot, Consumer<LibraryTile> onInsert) {
        this.projectRoot = projectRoot;
        this.onInsert = onInsert;

        ToggleGroup scopeGroup = new ToggleGroup();
        projectScope.setToggleGroup(scopeGroup);
        userScope.setToggleGroup(scopeGroup);
        projectScope.setSelected(true);
        projectScope.setDisable(projectRoot == null);
        if (projectRoot == null) {
            userScope.setSelected(true);
        }
        scopeGroup.selectedToggleProperty().addListener((obs, oldT, newT) -> refresh());

        list.setCellFactory(v -> new LibraryEntryCell());
        list.getSelectionModel().selectedItemProperty().addListener((obs, oldItem, newItem) ->
                updateButtons(newItem));

        insertButton.setDisable(true);
        deleteButton.setDisable(true);
        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> refresh());

        insertButton.setOnAction(e -> {
            LibraryTileMetadata selected = list.getSelectionModel().getSelectedItem();
            if (selected == null) {
                return;
            }
            store.read(currentDirectory(), selected.id()).ifPresent(onInsert);
        });
        deleteButton.setOnAction(e -> {
            LibraryTileMetadata selected = list.getSelectionModel().getSelectedItem();
            if (selected == null) {
                return;
            }
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Delete library entry \"" + selected.title() + "\"? This cannot be undone.",
                    ButtonType.OK, ButtonType.CANCEL);
            if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
                store.delete(currentDirectory(), selected.id());
                refresh();
            }
        });

        HBox scopeBar = new HBox(12, projectScope, userScope, refresh);
        scopeBar.setAlignment(Pos.CENTER_LEFT);
        scopeBar.setPadding(new Insets(8));

        HBox actionBar = new HBox(8, insertButton, deleteButton);
        actionBar.setPadding(new Insets(8));
        actionBar.setAlignment(Pos.CENTER_LEFT);

        setTop(scopeBar);
        setCenter(list);
        setBottom(actionBar);

        refresh();
    }

    /** Reloads the currently selected scope's entries — call after a save elsewhere (canvas
     * toolbar) so a newly added entry shows up without reopening this tab. */
    public void refresh() {
        list.getItems().setAll(store.list(currentDirectory()));
        updateButtons(list.getSelectionModel().getSelectedItem());
    }

    private void updateButtons(LibraryTileMetadata selected) {
        insertButton.setDisable(selected == null);
        deleteButton.setDisable(selected == null);
    }

    private Path currentDirectory() {
        LibraryScope scope = projectScope.isSelected() ? LibraryScope.PROJECT : LibraryScope.USER;
        return scope.directory(projectRoot);
    }

    private static final class LibraryEntryCell extends ListCell<LibraryTileMetadata> {
        @Override
        protected void updateItem(LibraryTileMetadata metadata, boolean empty) {
            super.updateItem(metadata, empty);
            if (empty || metadata == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            Label title = new Label(LibraryEntryPresentation.rowText(metadata));
            String validity = LibraryEntryPresentation.validityText(metadata, LocalDate.now());
            VBox box = new VBox(2, title);
            if (!validity.isBlank()) {
                Label validityLabel = new Label(validity);
                validityLabel.setTextFill(metadata.isStale(LocalDate.now()) ? Color.FIREBRICK : Color.GRAY);
                validityLabel.setStyle("-fx-font-size: 10px;");
                box.getChildren().add(validityLabel);
            }
            setGraphic(box);
            setText(null);
        }
    }
}
