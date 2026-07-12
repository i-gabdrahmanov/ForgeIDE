package dev.forgeide.ui.importer;

import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.yaml.PipelineTemplates;
import dev.forgeide.importer.ImportResult;
import dev.forgeide.importer.ImportSession;
import dev.forgeide.importer.ImportWriter;
import dev.forgeide.importer.bind.TileBinding;
import dev.forgeide.importer.scaffold.PromptSection;
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
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

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
    private final Label sizeWarningLabel = new Label();
    private final Button importButton = new Button("Импортировать");
    private final Button bindManuallyButton = new Button("Привязать вручную…");

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
        sizeWarningLabel.setTextFill(Color.DARKORANGE);
        sizeWarningLabel.setPadding(new Insets(0, 12, 12, 12));
        setTop(new VBox(header, sizeWarningLabel));

        bindingList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(TileBinding binding, boolean empty) {
                super.updateItem(binding, empty);
                if (empty || binding == null) {
                    setText(null);
                    setTextFill(Color.BLACK);
                } else {
                    setText(ImportRowPresentation.rowText(binding));
                    setTextFill(ImportRowPresentation.isMatched(binding) ? Color.SEAGREEN
                            : ImportRowPresentation.isAmbiguous(binding) ? Color.DARKORANGE : Color.FIREBRICK);
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
            refreshSizeWarning();
        } catch (RuntimeException ex) {
            session = null;
            bindingList.setItems(FXCollections.observableArrayList());
            sizeWarningLabel.setText(null);
            showError("Ошибка сканирования: " + ex.getMessage());
        }
    }

    /** T34: the whole skill directory now rides into the target project, so a checkout that
     * accidentally carries a stray binary/data file is no longer silently dropped — it silently
     * ships instead. Warn before import rather than after. */
    private void refreshSizeWarning() {
        List<String> oversized = session.oversizedSkills();
        sizeWarningLabel.setText(oversized.isEmpty() ? null
                : "Подозрительно большой скилл (>10 МБ): " + String.join(", ", oversized)
                        + " — проверьте, не попали ли лишние бинарники/данные.");
    }

    private void bindSelectedManually() {
        TileBinding selected = bindingList.getSelectionModel().getSelectedItem();
        if (selected == null || session == null) {
            return;
        }
        // T33: an ambiguous auto-match already has its candidate sections — resolve by picking
        // one instead of running a file dialog that would just re-derive the same list.
        if (selected instanceof TileBinding.Ambiguous ambiguous) {
            SectionPickerDialog.show("Неоднозначный матч — " + ambiguous.key(), ambiguous.candidates(),
                    chosen -> {
                        session.resolveAmbiguous(ambiguous.key(), chosen);
                        refreshBindings();
                    });
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Выбрать файл для " + selected.key());
        File file = chooser.showOpenDialog(windowOf(this));
        if (file == null) {
            return;
        }
        Path chosenFile = file.toPath();
        try {
            // T33: a heading-bearing markdown file (e.g. another subagent-prompts.md) gets a
            // §-section picker so the tile only gets that section's body, not the whole file.
            List<PromptSection> sections = ImportSession.sectionsOf(chosenFile);
            if (sections.isEmpty()) {
                session.bindManually(selected.key(), chosenFile);
                refreshBindings();
            } else {
                SectionPickerDialog.show("§-секция для " + selected.key(), sections, chosen -> {
                    session.bindManuallyToSection(selected.key(), chosen);
                    refreshBindings();
                });
            }
        } catch (RuntimeException ex) {
            showError("Не удалось привязать файл: " + ex.getMessage());
        }
    }

    /** T35: a re-import must not silently clobber files touched since the last one (locally
     * edited prompts via the T20 inspector, judge scripts). {@link ImportWriter#plan} finds out
     * what would conflict before anything is written; only when there is nothing to confirm does
     * import go straight through, same as before this task. */
    private void doImport() {
        if (session == null || !session.isComplete()) {
            return;
        }
        try {
            ImportResult result = session.result();
            List<ImportWriter.FileDiff> conflicts = ImportWriter.plan(projectRoot, result).stream()
                    .filter(diff -> diff.status() == ImportWriter.FileStatus.MODIFIED)
                    .toList();
            if (conflicts.isEmpty()) {
                ImportWriter.write(projectRoot, result);
                onImported.run();
            } else {
                ImportConflictDialog.show(conflicts, confirmedOverwrites -> {
                    ImportWriter.write(projectRoot, result, confirmedOverwrites);
                    onImported.run();
                });
            }
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
