package dev.forgeide.ui.canvas;

import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.StepDefinition;
import dev.forgeide.core.pipeline.edit.PipelineDocument;
import dev.forgeide.core.pipeline.edit.PipelineEdits;
import dev.forgeide.core.pipeline.validation.PipelineError;
import dev.forgeide.core.pipeline.validation.PipelineValidator;
import dev.forgeide.core.pipeline.yaml.PipelineYaml;
import dev.forgeide.core.port.HarnessGuardPort;
import dev.forgeide.core.port.TileValidityChecker;
import dev.forgeide.ui.editor.HarnessPaths;
import dev.forgeide.ui.editor.TileEditorPanel;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * T22 top-level screen: the constructor's two representations of one {@link PipelineDocument}
 * (FR-2.7) — a "Canvas" tab (palette + {@link ConstructorCanvasView} + an editable inspector)
 * and a "YAML" tab (the same model, regenerated text, edited back in) — plus undo/redo and a
 * diff-gated save. Replaces {@link PipelineCanvasView} wherever there is a real project to save
 * into; the read-only bundled-template preview (no save target at all) still uses the plain
 * {@link CanvasView} via {@link PipelineCanvasView}.
 *
 * <p>Leaving the YAML tab with unparsable text bounces the tab selection right back (FR-2.7
 * "переключение без потери правок" — the only way to not lose an edit is to never silently
 * discard it): the model only ever changes through a successfully parsed YAML or a graph edit.
 */
public final class PipelineConstructorView extends BorderPane {

    private final Path projectRoot;
    private final Path pipelineFile;
    private final HarnessGuardPort harnessGuard;
    private final PipelineYaml pipelineYaml = new PipelineYaml();
    private final PipelineDocument document;
    private final ConstructorCanvasView canvas;
    private final TileEditorPanel inspector;
    private final TextArea yamlArea = new TextArea();
    private final Label yamlStatus = new Label();
    private final Button undoButton = new Button("Undo");
    private final Button redoButton = new Button("Redo");

    private String lastSavedYaml;

    public PipelineConstructorView(String title, Path pipelineFile, Path projectRoot, HarnessGuardPort harnessGuard,
                                    TileValidityChecker validityChecker, String defaultPipelineId, Runnable onBack) {
        this.projectRoot = projectRoot;
        this.pipelineFile = pipelineFile;
        this.harnessGuard = harnessGuard;

        String existingYaml = Files.isRegularFile(pipelineFile) ? readOrEmpty(pipelineFile) : "";
        this.lastSavedYaml = existingYaml;
        PipelineValidator.Options options = PipelineValidator.Options.withRoot(projectRoot);
        PipelineYaml.ParseResult parsed = existingYaml.isBlank()
                ? new PipelineYaml.ParseResult(Optional.of(new PipelineDefinition(defaultPipelineId, 1, List.of())), List.of())
                : pipelineYaml.parseLenient(existingYaml, options);

        PipelineDefinition initial = parsed.definition().orElseGet(() -> new PipelineDefinition(defaultPipelineId, 1, List.of()));
        this.document = new PipelineDocument(initial, options);

        Runnable onDocumentChanged = () -> {
            undoButton.setDisable(!document.canUndo());
            redoButton.setDisable(!document.canRedo());
        };
        this.canvas = new ConstructorCanvasView(document, validityChecker, onDocumentChanged);
        undoButton.setOnAction(e -> {
            document.undo();
            canvas.refresh();
        });
        redoButton.setOnAction(e -> {
            document.redo();
            canvas.refresh();
        });

        this.inspector = new TileEditorPanel(projectRoot, this::savePrompt, this::saveScript, null, null, this::saveConfig);
        canvas.selectedStepProperty().addListener((obs, oldStep, newStep) -> {
            if (newStep == null) {
                inspector.showEmpty();
            } else {
                List<PipelineError> errors = TileErrors.byStep(document.errors()).getOrDefault(newStep.id(), List.of());
                inspector.show(newStep, errors, validityChecker.check(newStep));
            }
        });

        SplitPane canvasSplit = new SplitPane(new TilePalette(), canvas, inspector);
        canvasSplit.setDividerPositions(0.14, 0.72);

        yamlArea.setText(pipelineYaml.serialize(document.current()));

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        Tab canvasTab = new Tab("Canvas", canvasSplit);
        Tab yamlTab = new Tab("YAML", buildYamlTab());
        tabs.getTabs().addAll(canvasTab, yamlTab);
        tabs.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (oldTab == yamlTab && newTab != yamlTab) {
                if (!tryApplyYamlText()) {
                    Platform.runLater(() -> tabs.getSelectionModel().select(yamlTab));
                    return;
                }
                canvas.refresh();
            }
            if (newTab == yamlTab) {
                yamlArea.setText(pipelineYaml.serialize(document.current()));
                yamlStatus.setText("");
            }
        });

        setTop(header(title, onBack));
        setCenter(tabs);
    }

    // ---- Canvas-tab save routing (mirrors PipelineCanvasView's T20 wiring) -------------------

    private void savePrompt(String stepId, Path absolutePath, String content) {
        writeDirect(absolutePath, content);
        document.revalidate(); // the prompt file's existence/content just changed on disk
        canvas.refresh();
    }

    private void saveScript(Path relativePath, String content) {
        if (harnessGuard != null && HarnessPaths.isUnderHarness(relativePath)) {
            harnessGuard.edit(projectRoot, HarnessPaths.harnessRelative(relativePath), content);
        } else {
            writeDirect(projectRoot.resolve(relativePath), content);
        }
        document.revalidate();
        canvas.refresh();
    }

    private void saveConfig(StepDefinition replacement) {
        document.apply(PipelineEdits.replaceStep(replacement.id(), replacement));
        canvas.refresh();
    }

    private static void writeDirect(Path absolute, String content) {
        try {
            if (absolute.getParent() != null) {
                Files.createDirectories(absolute.getParent());
            }
            Files.writeString(absolute, content);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write " + absolute, e);
        }
    }

    // ---- YAML tab -----------------------------------------------------------------------------

    private VBox buildYamlTab() {
        yamlArea.setStyle("-fx-font-family: monospace;");

        TextArea diffArea = new TextArea();
        diffArea.setEditable(false);
        diffArea.setStyle("-fx-font-family: monospace;");
        diffArea.setPrefRowCount(10);

        Button applyButton = new Button("Apply to canvas");
        applyButton.setOnAction(e -> tryApplyYamlText());

        Button diffButton = new Button("Preview diff");
        diffButton.setOnAction(e -> {
            if (tryApplyYamlText()) {
                diffArea.setText(TextDiff.render(lastSavedYaml, pipelineYaml.serialize(document.current())));
            }
        });

        Button saveButton = new Button("Save pipeline.yaml");
        saveButton.setOnAction(e -> save());

        yamlStatus.setTextFill(Color.web("#d93025"));
        yamlStatus.setWrapText(true);

        HBox toolbar = new HBox(8, applyButton, diffButton, saveButton, yamlStatus);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(6));

        VBox.setVgrow(yamlArea, Priority.ALWAYS);
        return new VBox(toolbar, yamlArea, diffArea);
    }

    /** Parses the YAML tab's text and, if it parses, applies it to the document as a single
     * undo step (FR-2.7). A parse failure leaves the document untouched and reports why. */
    private boolean tryApplyYamlText() {
        PipelineYaml.ParseResult result = pipelineYaml.parseLenient(yamlArea.getText(),
                PipelineValidator.Options.withRoot(projectRoot));
        if (result.definition().isEmpty()) {
            yamlStatus.setText("YAML could not be parsed: "
                    + result.errors().stream().map(Object::toString).reduce((a, b) -> a + "; " + b).orElse(""));
            return false;
        }
        PipelineDefinition parsedDefinition = result.definition().get();
        if (!parsedDefinition.equals(document.current())) {
            document.apply(PipelineEdits.replacePipeline(parsedDefinition));
        }
        yamlStatus.setText("");
        return true;
    }

    private void save() {
        if (!tryApplyYamlText()) {
            return;
        }
        canvas.refresh();
        String newYaml = pipelineYaml.serialize(document.current());
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                TextDiff.render(lastSavedYaml, newYaml), ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText("Diff before save (FR-2.7) — comments in the existing file, if any, are not preserved");
        confirm.getDialogPane().setPrefWidth(640);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        try {
            if (pipelineFile.getParent() != null) {
                Files.createDirectories(pipelineFile.getParent());
            }
            Files.writeString(pipelineFile, newYaml, StandardCharsets.UTF_8);
            lastSavedYaml = newYaml;
        } catch (IOException e) {
            new Alert(Alert.AlertType.ERROR, "Could not save " + pipelineFile + ": " + e.getMessage()).showAndWait();
        }
    }

    // ---- chrome ---------------------------------------------------------------------------

    private HBox header(String title, Runnable onBack) {
        Button back = new Button("← Back");
        back.setOnAction(e -> onBack.run());
        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        undoButton.setDisable(true);
        redoButton.setDisable(true);
        HBox header = new HBox(12, back, titleLabel, undoButton, redoButton);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(12));
        return header;
    }

    private static String readOrEmpty(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read " + file, e);
        }
    }
}
