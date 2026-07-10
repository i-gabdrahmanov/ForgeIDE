package dev.forgeide.ui.canvas;

import dev.forgeide.core.pipeline.AgentStep;
import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.StepDefinition;
import dev.forgeide.core.pipeline.TileValidity;
import dev.forgeide.core.pipeline.edit.PipelineDocument;
import dev.forgeide.core.pipeline.edit.PipelineEdits;
import dev.forgeide.core.pipeline.edit.StepDefaults;
import dev.forgeide.core.pipeline.edit.StepIds;
import dev.forgeide.core.pipeline.edit.StepKind;
import dev.forgeide.core.pipeline.validation.PipelineError;
import dev.forgeide.core.port.TileValidityChecker;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.CubicCurve;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;
import javafx.scene.transform.Scale;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The editable graph half of the T22 constructor (FR-2.5/FR-2.6): everything {@link CanvasView}
 * intentionally leaves out ("editing the graph is T22's job"). Same node-graph-on-a-{@link Pane}
 * approach (pan/pan/zoom, {@link CubicCurve} edges, {@link StepTileView} tiles reused as-is for
 * body/badges/validity dot) but wired to a mutable {@link PipelineDocument} instead of a frozen
 * {@link PipelineDefinition}:
 *
 * <ul>
 *   <li>dropping a {@link TilePalette} entry creates a step at the drop point (FR-2.5)</li>
 *   <li>dragging a tile moves it (position lives in the document, outside its undo history)</li>
 *   <li>dragging from a tile's right-edge connector to another tile adds a {@code depends_on}
 *       edge (FR-2.5 "протяжка ребра между плитками = depends_on")</li>
 *   <li>a tile's context menu duplicates or deletes it; Delete/Backspace deletes the selection</li>
 * </ul>
 *
 * <p>Every mutation goes through {@link PipelineDocument#apply}, then calls {@link #refresh()};
 * external actions (undo/redo, the YAML tab applying a parsed edit, a config-form save) call
 * {@link #refresh()} themselves so the canvas never renders anything but {@code
 * document.current()}/{@code document.errors()}.
 */
public final class ConstructorCanvasView extends BorderPane {

    private static final double MIN_SCALE = 0.15;
    private static final double MAX_SCALE = 2.5;

    /** T23/FR-2.8: fired once, right after a fresh {@code AgentStep} default is applied to the
     * document — the sole hook that turns {@link StepDefaults}' non-existent {@code
     * prompts/<id>.md} into a real, contract-carrying file on disk (see {@link
     * dev.forgeide.core.pipeline.edit.AgentPromptScaffold}). {@code null} means read-only (no
     * project to write into), same convention as every other handler in this constructor family. */
    @FunctionalInterface
    public interface NewAgentPromptHandler {
        void created(AgentStep step);
    }

    /** T23/FR-2.9: "сохранение плитки или выделенного подграфа в библиотеку" — invoked with the
     * current ctrl/cmd-click selection in pipeline order when the toolbar's library button is
     * pressed; {@code null} means read-only, same convention as every other handler here. */
    @FunctionalInterface
    public interface LibrarySaveHandler {
        void save(List<StepDefinition> selectedSteps);
    }

    private final PipelineDocument document;
    private final TileValidityChecker validityChecker;
    private final Runnable onDocumentChanged;
    private final NewAgentPromptHandler newAgentPromptHandler;
    private final LibrarySaveHandler librarySaveHandler;
    private final Pane world = new Pane();
    private final Scale scale = new Scale(1, 1, 0, 0);
    private final Map<String, StepTileView> tilesById = new LinkedHashMap<>();
    private final ObjectProperty<StepDefinition> selectedStep = new SimpleObjectProperty<>();
    private final Label banner = new Label();
    /** T23: the ctrl/cmd-click "save this subgraph to the library" selection — independent of
     * {@link #selectedStepId} (the inspector's single-tile focus, unchanged by T22). */
    private final Set<String> librarySelection = new LinkedHashSet<>();
    private final Button saveToLibraryButton = new Button("Save to library…");

    private String selectedStepId;
    private Line pendingConnector;

    private double dragAnchorX;
    private double dragAnchorY;
    private double dragStartTranslateX;
    private double dragStartTranslateY;

    public ConstructorCanvasView(PipelineDocument document, TileValidityChecker validityChecker) {
        this(document, validityChecker, () -> { });
    }

    /** @param onDocumentChanged notified at the end of every {@link #refresh()} (this view's own
     *                           gestures included) — e.g. so an owning screen can keep undo/redo
     *                           button state in sync without polling. */
    public ConstructorCanvasView(PipelineDocument document, TileValidityChecker validityChecker,
                                  Runnable onDocumentChanged) {
        this(document, validityChecker, onDocumentChanged, null, null);
    }

    public ConstructorCanvasView(PipelineDocument document, TileValidityChecker validityChecker,
                                  Runnable onDocumentChanged, NewAgentPromptHandler newAgentPromptHandler,
                                  LibrarySaveHandler librarySaveHandler) {
        this.document = document;
        this.validityChecker = validityChecker;
        this.onDocumentChanged = onDocumentChanged;
        this.newAgentPromptHandler = newAgentPromptHandler;
        this.librarySaveHandler = librarySaveHandler;
        getStylesheets().add(CanvasView.class.getResource("canvas.css").toExternalForm());

        world.getTransforms().add(scale);
        StackPane viewport = new StackPane(world);
        viewport.setAlignment(Pos.TOP_LEFT);
        viewport.setStyle("-fx-background-color: white;");
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(viewport.widthProperty());
        clip.heightProperty().bind(viewport.heightProperty());
        viewport.setClip(clip);
        wirePanAndZoom(viewport);
        wirePaletteDrop(viewport);

        setTop(toolbar());
        setCenter(viewport);

        setFocusTraversable(true);
        setOnKeyPressed(e -> {
            if ((e.getCode() == KeyCode.DELETE || e.getCode() == KeyCode.BACK_SPACE) && selectedStepId != null) {
                deleteStep(selectedStepId);
                e.consume();
            }
        });

        refresh();
    }

    public ObjectProperty<StepDefinition> selectedStepProperty() {
        return selectedStep;
    }

    public void resetView() {
        scale.setX(1);
        scale.setY(1);
        world.setTranslateX(0);
        world.setTranslateY(0);
    }

    /** Rebuilds every tile/edge from {@code document.current()}/{@code document.errors()}. Call
     * after any change to the document, whatever triggered it (this view's own gestures, an
     * undo/redo, a YAML-tab apply, or a config-form save). */
    public void refresh() {
        world.getChildren().clear();
        tilesById.clear();

        PipelineDefinition pipeline = document.current();
        CanvasLayout.Result autoLayout = CanvasLayout.layout(pipeline);
        Map<String, List<PipelineError>> errorsByStep = TileErrors.byStep(document.errors());

        Map<String, double[]> effective = new LinkedHashMap<>();
        double maxX = 0;
        double maxY = 0;
        for (StepDefinition step : pipeline.steps()) {
            CanvasLayout.Position auto = autoLayout.positions().get(step.id());
            double[] xy = document.position(step.id())
                    .map(p -> new double[]{p.x(), p.y()})
                    .orElseGet(() -> auto == null ? new double[]{20, 20} : new double[]{auto.x(), auto.y()});
            effective.put(step.id(), xy);
            maxX = Math.max(maxX, xy[0] + CanvasLayout.TILE_WIDTH);
            maxY = Math.max(maxY, xy[1] + CanvasLayout.TILE_HEIGHT);
        }
        world.setPrefSize(Math.max(maxX + 120, 500), Math.max(maxY + 120, 400));

        drawEdges(autoLayout.edges(), effective);
        drawTiles(pipeline, effective, errorsByStep);
        updateBanner();
        restoreSelection(pipeline);
        pruneLibrarySelection(pipeline);
        onDocumentChanged.run();
    }

    // ---- construction -----------------------------------------------------------------------

    private HBox toolbar() {
        Button reset = new Button("Reset view");
        reset.setOnAction(e -> resetView());
        banner.getStyleClass().add("pipeline-error-banner");
        banner.setVisible(false);
        banner.setManaged(false);

        saveToLibraryButton.setTooltip(new Tooltip(
                "Ctrl/Cmd-click tiles to select a subgraph, or a single click to select one tile"));
        saveToLibraryButton.setDisable(true);
        saveToLibraryButton.setOnAction(e -> {
            if (librarySaveHandler == null || librarySelection.isEmpty()) {
                return;
            }
            librarySaveHandler.save(document.current().steps().stream()
                    .filter(s -> librarySelection.contains(s.id()))
                    .toList());
        });

        HBox bar = new HBox(12, reset, saveToLibraryButton, banner);
        bar.setPadding(new Insets(6, 10, 6, 10));
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    /** Drops any id the last edit removed, and keeps the toolbar button's enabled state (which
     * depends on both a non-empty selection and a wired handler) correct after every rebuild. */
    private void pruneLibrarySelection(PipelineDefinition pipeline) {
        Set<String> liveIds = idsOf(pipeline);
        librarySelection.retainAll(liveIds);
        applyLibrarySelectionStyles();
        saveToLibraryButton.setDisable(librarySaveHandler == null || librarySelection.isEmpty());
    }

    private void applyLibrarySelectionStyles() {
        tilesById.forEach((id, tile) -> {
            if (librarySelection.contains(id)) {
                if (!tile.getStyleClass().contains("in-selection")) {
                    tile.getStyleClass().add("in-selection");
                }
            } else {
                tile.getStyleClass().remove("in-selection");
            }
        });
    }

    private void updateBanner() {
        List<PipelineError> pipelineErrors = TileErrors.pipelineLevel(document.errors());
        if (pipelineErrors.isEmpty()) {
            banner.setVisible(false);
            banner.setManaged(false);
            return;
        }
        banner.setText(pipelineErrors.size() + " pipeline-level error(s): "
                + pipelineErrors.stream().map(PipelineError::message).reduce((a, b) -> a + "; " + b).orElse(""));
        banner.setVisible(true);
        banner.setManaged(true);
    }

    private void restoreSelection(PipelineDefinition pipeline) {
        if (selectedStepId != null && pipeline.steps().stream().anyMatch(s -> s.id().equals(selectedStepId))) {
            select(pipeline.step(selectedStepId));
        } else {
            selectedStepId = null;
            selectedStep.set(null);
        }
    }

    private void drawTiles(PipelineDefinition pipeline, Map<String, double[]> effective,
                            Map<String, List<PipelineError>> errorsByStep) {
        for (StepDefinition step : pipeline.steps()) {
            double[] xy = effective.get(step.id());
            List<PipelineError> stepErrors = errorsByStep.getOrDefault(step.id(), List.of());
            TileValidity validity = validityChecker.check(step);
            StepTileView tile = new StepTileView(step, stepErrors, validity, this::select);
            tile.setLayoutX(xy[0]);
            tile.setLayoutY(xy[1]);
            wireMove(tile, step.id());
            wireConnector(tile, step.id());
            wireContextMenu(tile, step.id());
            wireLibrarySelection(tile, step);
            tilesById.put(step.id(), tile);
            world.getChildren().add(tile);
        }
    }

    private void drawEdges(List<CanvasLayout.Edge> edges, Map<String, double[]> effective) {
        for (CanvasLayout.Edge edge : edges) {
            double[] from = effective.get(edge.from());
            double[] to = effective.get(edge.to());
            if (from == null || to == null) {
                continue;
            }
            double startX = from[0] + CanvasLayout.TILE_WIDTH;
            double startY = from[1] + CanvasLayout.TILE_HEIGHT / 2;
            double endX = to[0];
            double endY = to[1] + CanvasLayout.TILE_HEIGHT / 2;
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

    // ---- editing gestures ---------------------------------------------------------------------

    private void wireMove(StepTileView tile, String stepId) {
        double[] anchor = new double[2];
        double[] tileStart = new double[2];
        tile.setOnMousePressed(e -> {
            requestFocus();
            Point2D local = world.sceneToLocal(e.getSceneX(), e.getSceneY());
            anchor[0] = local.getX();
            anchor[1] = local.getY();
            tileStart[0] = tile.getLayoutX();
            tileStart[1] = tile.getLayoutY();
            e.consume(); // must not also start a viewport pan (see wirePanAndZoom)
        });
        tile.setOnMouseDragged(e -> {
            Point2D local = world.sceneToLocal(e.getSceneX(), e.getSceneY());
            tile.setLayoutX(tileStart[0] + (local.getX() - anchor[0]));
            tile.setLayoutY(tileStart[1] + (local.getY() - anchor[1]));
            e.consume();
        });
        tile.setOnMouseReleased(e -> {
            document.setPosition(stepId, tile.getLayoutX(), tile.getLayoutY());
            refresh(); // redraws edges against the tile's new position
            e.consume();
        });
    }

    /** A small handle on the tile's right edge: dragging from it to another tile adds a {@code
     * depends_on} edge from this step to that one (FR-2.5). */
    private void wireConnector(StepTileView tile, String stepId) {
        Circle connector = new Circle(5);
        connector.getStyleClass().add("connector-handle");
        StackPane.setAlignment(connector, Pos.CENTER_RIGHT);
        StackPane.setMargin(connector, new Insets(0, -6, 0, 0));
        tile.getChildren().add(connector);

        connector.setOnMousePressed(e -> {
            Point2D start = world.sceneToLocal(e.getSceneX(), e.getSceneY());
            Line line = new Line(start.getX(), start.getY(), start.getX(), start.getY());
            line.getStyleClass().add("edge-pending");
            world.getChildren().add(line);
            pendingConnector = line;
            e.consume();
        });
        connector.setOnMouseDragged(e -> {
            if (pendingConnector != null) {
                Point2D p = world.sceneToLocal(e.getSceneX(), e.getSceneY());
                pendingConnector.setEndX(p.getX());
                pendingConnector.setEndY(p.getY());
            }
            e.consume();
        });
        connector.setOnMouseReleased(e -> {
            if (pendingConnector != null) {
                world.getChildren().remove(pendingConnector);
                pendingConnector = null;
            }
            Point2D p = world.sceneToLocal(e.getSceneX(), e.getSceneY());
            findTileAt(p).filter(targetId -> !targetId.equals(stepId)).ifPresent(targetId -> {
                document.apply(PipelineEdits.addDependency(stepId, targetId));
                refresh();
            });
            e.consume();
        });
    }

    private Optional<String> findTileAt(Point2D local) {
        for (Map.Entry<String, StepTileView> entry : tilesById.entrySet()) {
            if (entry.getValue().getBoundsInParent().contains(local)) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }

    /** T23/FR-2.9: layered on top of {@link StepTileView}'s own {@code onSelect} plumbing — a
     * plain click still drives the inspector via {@link #select} exactly as T22 left it; a
     * ctrl/cmd-click additionally toggles this tile in {@link #librarySelection} without
     * disturbing that inspector focus, so "select a subgraph" and "inspect one tile" stay
     * independent gestures. */
    private void wireLibrarySelection(StepTileView tile, StepDefinition step) {
        tile.setOnMouseClicked(e -> {
            if (e.isShortcutDown()) {
                if (!librarySelection.remove(step.id())) {
                    librarySelection.add(step.id());
                }
            } else {
                librarySelection.clear();
                librarySelection.add(step.id());
            }
            applyLibrarySelectionStyles();
            saveToLibraryButton.setDisable(librarySaveHandler == null || librarySelection.isEmpty());
            select(step);
            e.consume();
        });
    }

    private void wireContextMenu(StepTileView tile, String stepId) {
        ContextMenu menu = new ContextMenu();
        MenuItem duplicate = new MenuItem("Duplicate");
        duplicate.setOnAction(e -> duplicateStep(stepId));
        MenuItem delete = new MenuItem("Delete");
        delete.setOnAction(e -> deleteStep(stepId));
        menu.getItems().addAll(duplicate, delete);
        tile.setOnContextMenuRequested(e -> menu.show(tile, e.getScreenX(), e.getScreenY()));
    }

    private void duplicateStep(String stepId) {
        String newId = StepIds.next(stepId, idsOf(document.current()));
        document.apply(PipelineEdits.duplicateStep(stepId, newId));
        document.position(stepId).ifPresent(p -> document.setPosition(newId, p.x() + 30, p.y() + 30));
        refresh();
    }

    private void deleteStep(String stepId) {
        document.apply(PipelineEdits.removeStep(stepId));
        document.removePosition(stepId);
        refresh();
    }

    private void wirePaletteDrop(StackPane viewport) {
        viewport.setOnDragOver(e -> {
            if (e.getDragboard().hasContent(TilePalette.STEP_KIND)) {
                e.acceptTransferModes(TransferMode.COPY);
            }
            e.consume();
        });
        viewport.setOnDragDropped(e -> {
            boolean success = false;
            if (e.getDragboard().hasContent(TilePalette.STEP_KIND)) {
                StepKind kind = StepKind.valueOf((String) e.getDragboard().getContent(TilePalette.STEP_KIND));
                addStepAt(kind, e.getSceneX(), e.getSceneY());
                success = true;
            }
            e.setDropCompleted(success);
            e.consume();
        });
    }

    private void addStepAt(StepKind kind, double sceneX, double sceneY) {
        String id = StepIds.next(kind, idsOf(document.current()));
        Point2D local = world.sceneToLocal(sceneX, sceneY);
        document.apply(PipelineEdits.addStep(StepDefaults.create(kind, id)));
        document.setPosition(id, local.getX() - CanvasLayout.TILE_WIDTH / 2, local.getY() - CanvasLayout.TILE_HEIGHT / 2);
        if (kind == StepKind.AGENT && newAgentPromptHandler != null) {
            newAgentPromptHandler.created((AgentStep) document.current().step(id));
            document.revalidate(); // the handler just seeded the prompt file this step points at
        }
        refresh();
        select(document.current().step(id));
    }

    /** T23/FR-2.9: applies a library insertion's already-rewired steps (fresh ids, rebound
     * prompt/script paths) as one undo step — the caller has already written {@code
     * LibraryTileInsertion.Result#files()} to disk before calling this. */
    public void insertSteps(List<StepDefinition> steps) {
        for (StepDefinition step : steps) {
            document.apply(PipelineEdits.addStep(step));
        }
        refresh();
        if (!steps.isEmpty()) {
            select(document.current().step(steps.get(steps.size() - 1).id()));
        }
    }

    private static Set<String> idsOf(PipelineDefinition pipeline) {
        Set<String> ids = new LinkedHashSet<>();
        pipeline.steps().forEach(s -> ids.add(s.id()));
        return ids;
    }

    // ---- pan / zoom / selection ---------------------------------------------------------------

    private void wirePanAndZoom(StackPane viewport) {
        viewport.setOnMousePressed(e -> {
            requestFocus();
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
        selectedStepId = step.id();
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
