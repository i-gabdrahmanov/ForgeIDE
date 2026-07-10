package dev.forgeide.ui.importer;

import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.yaml.PipelineTemplates;
import dev.forgeide.importer.ImportSession;
import dev.forgeide.importer.ImportWriter;
import dev.forgeide.importer.bind.TileBinding;
import dev.forgeide.importer.scaffold.ScaffoldCatalog;
import dev.forgeide.importer.scaffold.ScaffoldScanner;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Path;

/**
 * T24: scan a Forge-обвязка checkout, bind it against a bundled template, highlight anything
 * that did not auto-match, and let the user point unmatched tiles at a file by hand before
 * writing {@code pipeline.yaml} + {@code prompts/} into the current project (SD §8). One {@link
 * ImportSession} lives per (source directory, template) pick — choosing either again starts a
 * fresh one, same "no partial carry-over" doctrine other T22 screens use for their own resets.
 */
public final class ImportView extends BorderPane {

    private final Path projectRoot;
    private final Runnable onImported;

    private final Label sourceLabel = new Label("(каталог обвязки не выбран)");
    private final ChoiceBox<String> templateChoice =
            new ChoiceBox<>(FXCollections.observableArrayList("forgelite", "feature-pipeline"));
    private final ListView<TileBinding> bindingList = new ListView<>();
    private final Label statusLabel = new Label();
    private final Button importButton = new Button("Импортировать");
    private final Button bindManuallyButton = new Button("Выбрать файл вручную…");

    private Path sourceRoot;
    private ImportSession session;

    public ImportView(Path projectRoot, Runnable onBack, Runnable onImported) {
        this.projectRoot = projectRoot;
        this.onImported = onImported;

        Button back = new Button("← Назад");
        back.setOnAction(e -> onBack.run());
        Button chooseSource = new Button("Выбрать каталог обвязки…");
        chooseSource.setOnAction(e -> chooseSource());
        templateChoice.getSelectionModel().selectFirst();
        templateChoice.getSelectionModel().selectedItemProperty().addListener((obs, old, val) -> rescan());

        HBox header = new HBox(12, back, chooseSource, new Label("Шаблон:"), templateChoice, sourceLabel);
        header.setPadding(new Insets(12));
        setTop(header);

        bindingList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(TileBinding binding, boolean empty) {
                super.updateItem(binding, empty);
                if (empty || binding == null) {
                    setText(null);
                    setTextFill(Color.BLACK);
                } else {
                    setText(ImportRowPresentation.rowText(binding));
                    setTextFill(ImportRowPresentation.isMatched(binding) ? Color.SEAGREEN : Color.FIREBRICK);
                }
            }
        });
        setCenter(bindingList);

        bindManuallyButton.setDisable(true);
        bindingList.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) ->
                bindManuallyButton.setDisable(sel == null || ImportRowPresentation.isMatched(sel)));
        bindManuallyButton.setOnAction(e -> bindSelectedManually());

        importButton.setDisable(true);
        importButton.setOnAction(e -> doImport());

        HBox footer = new HBox(12, bindManuallyButton, importButton, statusLabel);
        footer.setPadding(new Insets(12));
        setBottom(footer);
    }

    private void chooseSource() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Каталог Forge-обвязки");
        File dir = chooser.showDialog(windowOf(this));
        if (dir == null) {
            return;
        }
        sourceRoot = dir.toPath();
        sourceLabel.setText(sourceRoot.toString());
        rescan();
    }

    private void rescan() {
        if (sourceRoot == null) {
            return;
        }
        try {
            ScaffoldCatalog catalog = ScaffoldScanner.scan(sourceRoot);
            PipelineDefinition template = "feature-pipeline".equals(templateChoice.getValue())
                    ? PipelineTemplates.featurePipeline()
                    : PipelineTemplates.forgelite();
            session = new ImportSession(template, catalog);
            refreshBindings();
        } catch (RuntimeException ex) {
            session = null;
            bindingList.setItems(FXCollections.observableArrayList());
            showError("Ошибка сканирования: " + ex.getMessage());
        }
    }

    private void bindSelectedManually() {
        TileBinding selected = bindingList.getSelectionModel().getSelectedItem();
        if (selected == null || session == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Выбрать файл для " + selected.key());
        File file = chooser.showOpenDialog(windowOf(this));
        if (file == null) {
            return;
        }
        try {
            session.bindManually(selected.key(), file.toPath());
            refreshBindings();
        } catch (RuntimeException ex) {
            showError("Не удалось привязать файл: " + ex.getMessage());
        }
    }

    private void doImport() {
        if (session == null || !session.isComplete()) {
            return;
        }
        try {
            ImportWriter.write(projectRoot, session.result());
            onImported.run();
        } catch (RuntimeException ex) {
            showError("Ошибка импорта: " + ex.getMessage());
        }
    }

    private void refreshBindings() {
        bindingList.setItems(FXCollections.observableArrayList(session.bindings()));
        statusLabel.setText(ImportRowPresentation.summary(session.bindings()));
        statusLabel.setTextFill(session.isComplete() ? Color.SEAGREEN : Color.DARKORANGE);
        importButton.setDisable(!session.isComplete());
    }

    private void showError(String message) {
        statusLabel.setText(message);
        statusLabel.setTextFill(Color.FIREBRICK);
        importButton.setDisable(true);
    }

    private Window windowOf(Node node) {
        return node.getScene() == null ? null : node.getScene().getWindow();
    }
}
