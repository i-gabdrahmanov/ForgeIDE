package dev.forgeide.ui.canvas;

import dev.forgeide.core.pipeline.StepDefinition;
import dev.forgeide.core.pipeline.TileValidity;
import dev.forgeide.core.pipeline.TileValidityStatus;
import dev.forgeide.core.pipeline.validation.PipelineError;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.List;

/**
 * Read-only inspector for the selected tile (SD §7 "Инспектор плитки", FR-2.4: prompt/script/
 * config, validation errors, registry validity). No editing — that is T20/T22.
 */
public final class TileDetailPanel extends ScrollPane {

    private final VBox content = new VBox(8);

    public TileDetailPanel() {
        content.setPadding(new Insets(12));
        setContent(content);
        setFitToWidth(true);
        setPrefWidth(320);
        showEmpty();
    }

    public void showEmpty() {
        content.getChildren().setAll(new Label("Select a tile to see its details."));
    }

    public void show(StepDefinition step, List<PipelineError> errors, TileValidity validity) {
        content.getChildren().clear();

        Label title = new Label(step.id());
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        Label type = new Label(StepTileStyles.typeLabel(step));
        type.setStyle("-fx-text-fill: #666666;");
        content.getChildren().addAll(title, type);

        if (!errors.isEmpty()) {
            content.getChildren().add(errorsSection(errors));
        }
        if (validity.status() != TileValidityStatus.UNKNOWN) {
            content.getChildren().add(validityLine(validity));
        }

        content.getChildren().add(new Separator());
        for (StepDetailFields.Field field : StepDetailFields.of(step)) {
            content.getChildren().addAll(fieldLabel(field.label()), fieldValue(field.value()));
        }
    }

    private VBox errorsSection(List<PipelineError> errors) {
        Label heading = new Label("Validation errors");
        heading.setStyle("-fx-font-weight: bold; -fx-text-fill: #d93025;");
        VBox box = new VBox(2, heading);
        for (PipelineError error : errors) {
            Label line = new Label("• [" + error.field() + "] " + error.message());
            line.setWrapText(true);
            line.setTextFill(Color.web("#d93025"));
            box.getChildren().add(line);
        }
        return box;
    }

    private Label validityLine(TileValidity validity) {
        Label line = new Label("Validity: " + validity.status()
                + (validity.detail().isBlank() ? "" : " — " + validity.detail()));
        line.setTextFill(validity.status() == TileValidityStatus.STALE ? Color.web("#f9ab00") : Color.web("#34a853"));
        return line;
    }

    private Label fieldLabel(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-weight: bold; -fx-font-size: 11px; -fx-text-fill: #555555;");
        return label;
    }

    private Label fieldValue(String text) {
        Label value = new Label(text);
        value.setWrapText(true);
        return value;
    }
}
