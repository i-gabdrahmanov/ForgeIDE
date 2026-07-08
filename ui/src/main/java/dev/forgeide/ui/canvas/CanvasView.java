package dev.forgeide.ui.canvas;

import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.StepDefinition;
import dev.forgeide.core.pipeline.TileValidity;
import dev.forgeide.core.pipeline.validation.PipelineError;
import dev.forgeide.core.port.TileValidityChecker;
import dev.forgeide.core.run.RunSnapshot;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.CubicCurve;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;
import javafx.scene.transform.Scale;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read-only node-graph canvas (SD §7, T05): tiles placed by {@link CanvasLayout}, edges drawn as
 * {@link CubicCurve}s (no off-the-shelf FX-graph library — SD §7 notes they are unmaintained),
 * pan (drag) and zoom (scroll) over a clipped viewport. Editing the graph is T22's job — this
 * view only reports the selected tile via {@link #selectedStepProperty()}.
 */
public final class CanvasView extends BorderPane {

    private static final double MIN_SCALE = 0.15;
    private static final double MAX_SCALE = 2.5;

    private final Pane world = new Pane();
    private final Scale scale = new Scale(1, 1, 0, 0);
    private final ObjectProperty<StepDefinition> selectedStep = new SimpleObjectProperty<>();
    private final Map<String, StepTileView> tilesById = new LinkedHashMap<>();

    private double dragAnchorX;
    private double dragAnchorY;
    private double dragStartTranslateX;
    private double dragStartTranslateY;

    public CanvasView(PipelineDefinition pipeline, List<PipelineError> errors, TileValidityChecker validityChecker) {
        getStylesheets().add(CanvasView.class.getResource("canvas.css").toExternalForm());
        Map<String, List<PipelineError>> errorsByStep = TileErrors.byStep(errors);

        CanvasLayout.Result layout = CanvasLayout.layout(pipeline);
        world.setPrefSize(Math.max(layout.width(), 1), Math.max(layout.height(), 1));
        world.getTransforms().add(scale);

        drawEdges(layout);
        drawTiles(pipeline, layout, errorsByStep, validityChecker);

        StackPane viewport = new StackPane(world);
        viewport.setAlignment(Pos.TOP_LEFT);
        viewport.setStyle("-fx-background-color: white;");
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(viewport.widthProperty());
        clip.heightProperty().bind(viewport.heightProperty());
        viewport.setClip(clip);
        wirePanAndZoom(viewport);

        setTop(toolbar(errors));
        setCenter(viewport);
    }

    public ObjectProperty<StepDefinition> selectedStepProperty() {
        return selectedStep;
    }

    /**
     * Overlays a run's live status onto the tiles built from the static {@link
     * PipelineDefinition} (SD §7, T10). A step id with no matching tile — a {@code
     * per_task_loop}-unrolled instance, since this canvas is built once from the static
     * definition — has nothing to overlay onto and is skipped; see the T10 plan's known
     * limitations.
     */
    public void applyRunSnapshot(RunSnapshot snapshot) {
        snapshot.steps().forEach(stepSnapshot -> {
            StepTileView tile = tilesById.get(stepSnapshot.stepId());
            if (tile != null) {
                tile.applyRunStatus(Optional.of(stepSnapshot));
            }
        });
    }

    public void resetView() {
        scale.setX(1);
        scale.setY(1);
        world.setTranslateX(0);
        world.setTranslateY(0);
    }

    // ---- construction ---------------------------------------------------------------------

    private HBox toolbar(List<PipelineError> errors) {
        Button reset = new Button("Reset view");
        reset.setOnAction(e -> resetView());
        HBox bar = new HBox(12, reset);
        bar.setPadding(new Insets(6, 10, 6, 10));
        bar.setAlignment(Pos.CENTER_LEFT);

        List<PipelineError> pipelineErrors = TileErrors.pipelineLevel(errors);
        if (!pipelineErrors.isEmpty()) {
            String text = pipelineErrors.size() + " pipeline-level error(s): "
                    + pipelineErrors.stream().map(PipelineError::message).reduce((a, b) -> a + "; " + b).orElse("");
            Label banner = new Label(text);
            banner.getStyleClass().add("pipeline-error-banner");
            bar.getChildren().add(banner);
        }
        return bar;
    }

    private void drawTiles(PipelineDefinition pipeline, CanvasLayout.Result layout,
                            Map<String, List<PipelineError>> errorsByStep, TileValidityChecker validityChecker) {
        for (StepDefinition step : pipeline.steps()) {
            CanvasLayout.Position position = layout.positions().get(step.id());
            if (position == null) {
                continue;
            }
            List<PipelineError> stepErrors = errorsByStep.getOrDefault(step.id(), List.of());
            TileValidity validity = validityChecker.check(step);
            StepTileView tile = new StepTileView(step, stepErrors, validity, this::select);
            tile.setLayoutX(position.x());
            tile.setLayoutY(position.y());
            tilesById.put(step.id(), tile);
            world.getChildren().add(tile);
        }
    }

    private void drawEdges(CanvasLayout.Result layout) {
        for (CanvasLayout.Edge edge : layout.edges()) {
            CanvasLayout.Position from = layout.positions().get(edge.from());
            CanvasLayout.Position to = layout.positions().get(edge.to());
            if (from == null || to == null) {
                continue;
            }
            double startX = from.x() + CanvasLayout.TILE_WIDTH;
            double startY = from.y() + CanvasLayout.TILE_HEIGHT / 2;
            double endX = to.x();
            double endY = to.y() + CanvasLayout.TILE_HEIGHT / 2;
            double controlOffset = Math.max(30, (endX - startX) / 2);

            CubicCurve curve = new CubicCurve(startX, startY, startX + controlOffset, startY,
                    endX - controlOffset, endY, endX, endY);
            curve.setFill(Color.TRANSPARENT);
            curve.getStyleClass().add(edge.kind() == CanvasLayout.EdgeKind.BRANCH_ROUTE ? "edge-branch" : "edge-depends");
            world.getChildren().add(curve);

            if (!edge.label().isBlank()) {
                Text label = new Text((startX + endX) / 2, (startY + endY) / 2 - 4, edge.label());
                label.getStyleClass().add("edge-label");
                world.getChildren().add(label);
            }
        }
    }

    private void wirePanAndZoom(StackPane viewport) {
        viewport.setOnMousePressed(e -> {
            dragAnchorX = e.getSceneX();
            dragAnchorY = e.getSceneY();
            dragStartTranslateX = world.getTranslateX();
            dragStartTranslateY = world.getTranslateY();
        });
        viewport.setOnMouseDragged(e -> {
            world.setTranslateX(dragStartTranslateX + (e.getSceneX() - dragAnchorX));
            world.setTranslateY(dragStartTranslateY + (e.getSceneY() - dragAnchorY));
        });
        viewport.setOnScroll(e -> {
            double factor = e.getDeltaY() > 0 ? 1.1 : 1 / 1.1;
            double newScale = clamp(scale.getX() * factor, MIN_SCALE, MAX_SCALE);
            Point2D pivot = world.sceneToLocal(e.getSceneX(), e.getSceneY());
            scale.setPivotX(pivot.getX());
            scale.setPivotY(pivot.getY());
            scale.setX(newScale);
            scale.setY(newScale);
            e.consume();
        });
    }

    private void select(StepDefinition step) {
        tilesById.values().forEach(t -> t.getStyleClass().remove("selected"));
        StepTileView tile = tilesById.get(step.id());
        if (tile != null) {
            tile.getStyleClass().add("selected");
        }
        selectedStep.set(step);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
