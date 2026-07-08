package dev.forgeide.ui.canvas;

import dev.forgeide.core.pipeline.StepDefinition;
import dev.forgeide.core.pipeline.TileValidity;
import dev.forgeide.core.pipeline.validation.PipelineError;
import dev.forgeide.core.run.StepSnapshot;
import dev.forgeide.core.run.StepStatus;
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
import java.util.Optional;
import java.util.function.Consumer;

/**
 * A single canvas tile (SD §7: "узел = Region со стилизацией CSS по типу шага и статусу").
 * {@link StackPane} is a {@link javafx.scene.layout.Region}; it gives the error-badge / validity
 * dot overlay for free. Read-only: selecting a tile only reports it upward via {@code onSelect},
 * it never mutates the model — editing the graph is T22's job.
 */
public final class StepTileView extends StackPane {

    private static final List<String> STATUS_STYLE_CLASSES = List.of(
            "status-pending", "status-ready", "status-running", "status-passed",
            "status-failed", "status-waiting-gate", "status-waiting-input", "status-skipped");

    private final StepDefinition step;
    private Label iterationBadge;
    private Label questionsBadge;

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

    /**
     * Overlays a run's live status onto this static tile (SD §7, T10: "оверлей статусов на
     * канвасе — цвет/бейдж по RunSnapshot, включая счётчик итераций, бейдж «N вопросов» для
     * WAITING_INPUT"). Style-class swap + label-text mutation only — never rebuilds the tile —
     * so repeated calls on every engine event stay well under NFR-2's 200ms budget.
     */
    public void applyRunStatus(Optional<StepSnapshot> snapshot) {
        getStyleClass().removeAll(STATUS_STYLE_CLASSES);
        if (snapshot.isEmpty()) {
            setIterationBadge(0);
            setQuestionsBadge(0);
            return;
        }
        StepSnapshot s = snapshot.get();
        getStyleClass().add(statusStyleClass(s.status()));
        setIterationBadge(s.iteration());
        setQuestionsBadge(s.status() == StepStatus.WAITING_INPUT ? s.pendingQuestions().size() : 0);
    }

    private static String statusStyleClass(StepStatus status) {
        return "status-" + status.name().toLowerCase().replace('_', '-');
    }

    private void setIterationBadge(int iteration) {
        if (iteration <= 0) {
            if (iterationBadge != null) {
                iterationBadge.setVisible(false);
            }
            return;
        }
        if (iterationBadge == null) {
            iterationBadge = new Label();
            iterationBadge.getStyleClass().add("iteration-badge");
            StackPane.setAlignment(iterationBadge, Pos.BOTTOM_LEFT);
            StackPane.setMargin(iterationBadge, new Insets(0, 0, 6, 6));
            getChildren().add(iterationBadge);
        }
        iterationBadge.setText("#" + iteration);
        iterationBadge.setVisible(true);
    }

    private void setQuestionsBadge(int questionCount) {
        if (questionCount <= 0) {
            if (questionsBadge != null) {
                questionsBadge.setVisible(false);
            }
            return;
        }
        if (questionsBadge == null) {
            questionsBadge = new Label();
            questionsBadge.getStyleClass().add("questions-badge");
            StackPane.setAlignment(questionsBadge, Pos.TOP_LEFT);
            StackPane.setMargin(questionsBadge, new Insets(4, 0, 0, 4));
            getChildren().add(questionsBadge);
        }
        questionsBadge.setText(questionCount + (questionCount == 1 ? " question" : " questions"));
        questionsBadge.setVisible(true);
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
