package dev.forgeide.ui.canvas;

import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.validation.PipelineError;
import dev.forgeide.core.pipeline.yaml.PipelineYaml;
import dev.forgeide.core.port.TileValidityChecker;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.List;

/**
 * Canvas screen (M1 acceptance: "открыть шаблон forgelite → граф на канвасе"; "невалидный
 * pipeline.yaml → бейджи на проблемных плитках"). Wraps {@link CanvasView} with a back button,
 * a title, and the {@link TileDetailPanel} for the selected tile. When the YAML could not be
 * turned into any model at all (malformed YAML / missing required field — see
 * {@link PipelineYaml#parseLenient(String)}), shows the pipeline-level errors instead of a
 * canvas since there is nothing to lay out.
 */
public final class PipelineCanvasView extends BorderPane {

    public PipelineCanvasView(String title, PipelineYaml.ParseResult parseResult,
                               TileValidityChecker validityChecker, Runnable onBack) {
        setTop(header(title, onBack));

        if (parseResult.definition().isEmpty()) {
            setCenter(unparsableMessage(parseResult.errors()));
            return;
        }

        PipelineDefinition pipeline = parseResult.definition().get();
        CanvasView canvas = new CanvasView(pipeline, parseResult.errors(), validityChecker);
        TileDetailPanel detail = new TileDetailPanel();
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
