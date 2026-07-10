package dev.forgeide.ui.canvas;

import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.validation.PipelineError;
import dev.forgeide.core.pipeline.yaml.PipelineYaml;
import dev.forgeide.core.port.HarnessGuardPort;
import dev.forgeide.core.port.TileValidityChecker;
import dev.forgeide.ui.editor.HarnessPaths;
import dev.forgeide.ui.editor.TileEditorPanel;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Canvas screen (M1 acceptance: "открыть шаблон forgelite → граф на канвасе"; "невалидный
 * pipeline.yaml → бейджи на проблемных плитках"). Wraps {@link CanvasView} with a back button,
 * a title, and a {@link TileEditorPanel} for the selected tile (T20). When the YAML could not be
 * turned into any model at all (malformed YAML / missing required field — see
 * {@link PipelineYaml#parseLenient(String)}), shows the pipeline-level errors instead of a
 * canvas since there is nothing to lay out.
 */
public final class PipelineCanvasView extends BorderPane {

    /** Read-only preview (SD §7's own M1 acceptance: bundled forgelite template, no project to
     * write into) — same as passing a {@code null} {@code projectRoot} below. */
    public PipelineCanvasView(String title, PipelineYaml.ParseResult parseResult,
                               TileValidityChecker validityChecker, Runnable onBack) {
        this(title, parseResult, validityChecker, null, null, onBack);
    }

    /**
     * T20/FR-8.1-8.3: an editable inspector for the idle (no run in progress) design-time canvas.
     * Saves write straight to disk — {@code projectRoot}/{@code harnessGuard} {@code null} means
     * there is nowhere to write (the template preview) and the inspector falls back to read-only.
     * Unlike {@link dev.forgeide.ui.run.RunView}'s mid-run inspector, there is no live {@code
     * PipelineEngine} here to route a command through, so nothing is audited — see the T20 plan's
     * "idle vs live" scope split.
     */
    public PipelineCanvasView(String title, PipelineYaml.ParseResult parseResult, TileValidityChecker validityChecker,
                               Path projectRoot, HarnessGuardPort harnessGuard, Runnable onBack) {
        setTop(header(title, onBack));

        if (parseResult.definition().isEmpty()) {
            setCenter(unparsableMessage(parseResult.errors()));
            return;
        }

        PipelineDefinition pipeline = parseResult.definition().get();
        CanvasView canvas = new CanvasView(pipeline, parseResult.errors(), validityChecker);
        TileEditorPanel detail = new TileEditorPanel(projectRoot,
                (stepId, absolutePath, content) -> writeDirect(absolutePath, content),
                (relativePath, content) -> {
                    if (harnessGuard != null && HarnessPaths.isUnderHarness(relativePath)) {
                        harnessGuard.edit(projectRoot, HarnessPaths.harnessRelative(relativePath), content);
                    } else {
                        writeDirect(projectRoot.resolve(relativePath), content);
                    }
                });
        canvas.selectedStepProperty().addListener((obs, oldStep, newStep) -> {
            if (newStep == null) {
                detail.showEmpty();
            } else {
                List<PipelineError> errors = TileErrors.byStep(parseResult.errors())
                        .getOrDefault(newStep.id(), List.of());
                detail.show(newStep, errors, validityChecker.check(newStep));
            }
        });

        SplitPane split = new SplitPane(canvas, detail);
        split.setDividerPositions(0.75);
        setCenter(split);
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

    private HBox header(String title, Runnable onBack) {
        Button back = new Button("← Back");
        back.setOnAction(e -> onBack.run());
        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        HBox header = new HBox(12, back, titleLabel);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(12));
        return header;
    }

    private VBox unparsableMessage(List<PipelineError> errors) {
        Label heading = new Label("pipeline.yaml could not be parsed:");
        heading.setStyle("-fx-font-weight: bold;");
        VBox box = new VBox(6, heading);
        box.setPadding(new Insets(16));
        for (PipelineError error : errors) {
            Label line = new Label("• " + error);
            line.setTextFill(Color.web("#d93025"));
            line.setWrapText(true);
            box.getChildren().add(line);
        }
        return box;
    }
}
