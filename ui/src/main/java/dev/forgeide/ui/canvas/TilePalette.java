package dev.forgeide.ui.canvas;

import dev.forgeide.core.pipeline.edit.StepKind;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.VBox;

/**
 * The tile-type palette (FR-2.5: "Палитра типов плиток; перетаскивание на канвас создаёт шаг").
 * One label per {@link StepKind}, styled with the same {@code tile-<type>} CSS classes the real
 * tiles use (canvas.css) so the palette entry previews the colour the dropped tile will get.
 * Dragging an entry starts a JavaFX drag-and-drop carrying the {@link StepKind} name in {@link
 * #STEP_KIND}; {@link ConstructorCanvasView} is the only drop target that understands it.
 */
public final class TilePalette extends VBox {

    public static final DataFormat STEP_KIND = new DataFormat("application/x-forgeide-step-kind");

    public TilePalette() {
        super(6);
        getStyleClass().add("tile-palette");
        setPadding(new Insets(8));
        Label heading = new Label("Palette");
        heading.getStyleClass().add("palette-heading");
        getChildren().add(heading);
        for (StepKind kind : StepKind.values()) {
            getChildren().add(paletteEntry(kind));
        }
    }

    private Label paletteEntry(StepKind kind) {
        Label label = new Label(kind.key());
        label.getStyleClass().addAll("palette-entry", "step-tile", "tile-" + kind.key());
        label.setMaxWidth(Double.MAX_VALUE);
        label.setOnDragDetected(e -> {
            Dragboard db = label.startDragAndDrop(TransferMode.COPY);
            ClipboardContent content = new ClipboardContent();
            content.put(STEP_KIND, kind.name());
            db.setContent(content);
            e.consume();
        });
        return label;
    }
}
