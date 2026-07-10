package dev.forgeide.core.pipeline.edit;

import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.validation.PipelineError;
import dev.forgeide.core.pipeline.validation.PipelineValidator;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The T22 constructor's single source of truth (FR-2.5-2.7). Canvas and YAML tab both render
 * off {@link #current()}, so switching between them never loses or duplicates an edit; undo/redo
 * is a stack of the {@link PipelineDefinition} snapshots an {@link PipelineEdit} passed through
 * (records are immutable — no inverse to get wrong), so both views resync to the same state a
 * {@link #undo()}/{@link #redo()} produces automatically.
 *
 * <p>Tile positions are tracked here too but deliberately outside the undo/redo history: {@code
 * pipeline.yaml} has no x/y fields (FR-2.1), so a position is canvas-only bookkeeping, not part
 * of "the model" undo/redo restores.
 */
public final class PipelineDocument {

    private final PipelineValidator validator = new PipelineValidator();
    private final PipelineValidator.Options options;
    private final Deque<PipelineDefinition> undoStack = new ArrayDeque<>();
    private final Deque<PipelineDefinition> redoStack = new ArrayDeque<>();
    private final Map<String, TilePosition> positions = new LinkedHashMap<>();

    private PipelineDefinition current;
    private List<PipelineError> errors;

    public PipelineDocument(PipelineDefinition initial) {
        this(initial, PipelineValidator.Options.none());
    }

    public PipelineDocument(PipelineDefinition initial, PipelineValidator.Options options) {
        this.current = Objects.requireNonNull(initial, "initial");
        this.options = Objects.requireNonNull(options, "options");
        this.errors = validator.validate(current, options);
    }

    public PipelineDefinition current() {
        return current;
    }

    /** FR-2.6: "тот же валидатор, что при загрузке", recomputed after every {@link #apply}. */
    public List<PipelineError> errors() {
        return errors;
    }

    /** Gates run-start (FR-2.6: "запуск прогона заблокирован до зелёной валидации"). */
    public boolean isValid() {
        return errors.isEmpty();
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    /** Applies {@code edit}; a no-op result (e.g. removing an edge that wasn't there) is
     * dropped rather than padding the undo stack with a step that would restore nothing. */
    public void apply(PipelineEdit edit) {
        PipelineDefinition next = edit.apply(current);
        if (next.equals(current)) {
            return;
        }
        undoStack.push(current);
        redoStack.clear();
        current = next;
        revalidate();
    }

    public void undo() {
        if (!canUndo()) {
            return;
        }
        redoStack.push(current);
        current = undoStack.pop();
        revalidate();
    }

    public void redo() {
        if (!canRedo()) {
            return;
        }
        undoStack.push(current);
        current = redoStack.pop();
        revalidate();
    }

    /** Forces a recheck against the current model without any edit — e.g. after a prompt file
     * was written to disk from the Prompt tab (T20), which the {@code checkPromptFile} rule
     * cares about but which is invisible to {@link #apply}'s no-op-edit short-circuit. */
    public void revalidate() {
        errors = validator.validate(current, options);
    }

    // ---- canvas-only tile positions ---------------------------------------------------------

    public Optional<TilePosition> position(String stepId) {
        return Optional.ofNullable(positions.get(stepId));
    }

    public void setPosition(String stepId, double x, double y) {
        positions.put(stepId, new TilePosition(x, y));
    }

    public void removePosition(String stepId) {
        positions.remove(stepId);
    }
}
