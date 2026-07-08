package dev.forgeide.ui.canvas;

import dev.forgeide.core.pipeline.StepDefinition;
import dev.forgeide.core.pipeline.TileValidity;
import dev.forgeide.core.pipeline.validation.PipelineError;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.TextAlignment;

import java.util.List;
import java.util.function.Consumer;

/**
 * A single canvas tile (SD §7: "узел = Region со стилизацией CSS по типу шага и статусу").
 * {@link StackPane} is a {@link javafx.scene.layout.Region}; it gives the error-badge / validity
 * dot overlay for free. Read-only: selecting a tile only reports it upward via {@code onSelect},
 * it never mutates the model — editing the graph is T22's job.
 */
public final class StepTileView extends StackPane {

    private final StepDefinition step;

    public StepTileView(StepDefinition step, List<PipelineError> errors, TileValidity validity,
                         Consumer<StepDefinition> onSelect) {
        this.step = step;
        setPrefSize(CanvasLayout.TILE_WIDTH, CanvasLayout.TILE_HEIGHT);
        setMinSize(CanvasLayout.TILE_WIDTH, CanvasLayout.TILE_HEIGHT);
        setMaxSize(CanvasLayout.TILE_WIDTH, CanvasLayout.TILE_HEIGHT);
        getStyleClass().addAll("step-tile", StepTileStyles.cssClass(step));
        if (!errors.isEmpty()) {
            getStyleClass().add("has-error");
        }

        VBox body = body(step);
        StackPane.setAlignment(body, Pos.TOP_LEFT);
        getChildren().add(body);

        if (!errors.isEmpty()) {
            Label badge = new Label(String.valueOf(errors.size()));
            badge.getStyleClass().add("error-badge");
            Tooltip.install(badge, new Tooltip(TileErrors.badgeText(errors)));
            StackPane.setAlignment(badge, Pos.TOP_RIGHT);
            StackPane.setMargin(badge, new Insets(4, 4, 0, 0));
            getChildren().add(badge);
        }

        Circle validityDot = new Circle(4, validityColor(validity));
        validityDot.getStyleClass().addAll("validity-dot", "validity-" + validity.status().name().toLowerCase());
        if (!validity.detail().isBlank()) {
            Tooltip.install(validityDot, new Tooltip(validity.detail()));
        }
        StackPane.setAlignment(validityDot, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(validityDot, new Insets(0, 6, 6, 0));
        getChildren().add(validityDot);

        setOnMouseClicked(e -> onSelect.accept(step));
    }

    public StepDefinition step() {
        return step;
    }

    private static VBox body(StepDefinition step) {
        Label type = new Label(StepTileStyles.typeLabel(step).toUpperCase());
        type.getStyleClass().add("tile-type");
        Label id = new Label(step.id());
        id.getStyleClass().add("tile-id");
        Label summary = new Label(StepTileStyles.summary(step));
        summary.getStyleClass().add("tile-summary");
        summary.setWrapText(true);
        summary.setTextAlignment(TextAlignment.LEFT);
        VBox box = new VBox(2, type, id, summary);
        box.setPadding(new Insets(6));
        box.setMaxWidth(CanvasLayout.TILE_WIDTH - 20);
        return box;
    }

    /** Fallback fill so the dot reads correctly even before the stylesheet is applied. */
    private static Color validityColor(TileValidity validity) {
        return switch (validity.status()) {
            case FRESH -> Color.web("#34a853");
            case STALE -> Color.web("#f9ab00");
            case UNKNOWN -> Color.web("#9aa0a6");
        };
    }
}
