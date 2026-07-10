package dev.forgeide.core.pipeline.edit;

import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.ScriptStep;
import dev.forgeide.core.pipeline.StepDefinition;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** T22 acceptance: "undo/redo восстанавливает граф и конфиги без рассинхроза с YAML-вкладкой" —
 * since the YAML tab always regenerates from {@link PipelineDocument#current()}, proving undo
 * restores {@code current()} bit-for-bit is exactly proving no such desync is possible. */
class PipelineDocumentTest {

    private static ScriptStep script(String id, List<String> dependsOn) {
        return new ScriptStep(id, dependsOn, List.of("echo"), Duration.ofSeconds(5));
    }

    @Test
    void startsValidatedAndEmptyHistory() {
        PipelineDocument doc = new PipelineDocument(new PipelineDefinition("p", 1, List.of(script("a", List.of()))));
        assertThat(doc.isValid()).isTrue();
        assertThat(doc.canUndo()).isFalse();
        assertThat(doc.canRedo()).isFalse();
    }

    @Test
    void applyPushesHistoryAndRevalidates() {
        PipelineDocument doc = new PipelineDocument(new PipelineDefinition("p", 1, List.of(script("a", List.of()))));
        doc.apply(PipelineEdits.addStep(script("b", List.of("missing"))));

        assertThat(doc.current().steps()).extracting(StepDefinition::id).containsExactly("a", "b");
        assertThat(doc.canUndo()).isTrue();
        assertThat(doc.isValid()).isFalse(); // dangling depends_on -> live-validation error (FR-2.6)
    }

    @Test
    void undoRestoresThePriorSnapshotExactlyAndRevalidates() {
        PipelineDefinition original = new PipelineDefinition("p", 1, List.of(script("a", List.of())));
        PipelineDocument doc = new PipelineDocument(original);

        doc.apply(PipelineEdits.addStep(script("b", List.of("missing"))));
        assertThat(doc.isValid()).isFalse();

        doc.undo();

        assertThat(doc.current()).isEqualTo(original);
        assertThat(doc.isValid()).isTrue();
        assertThat(doc.canUndo()).isFalse();
        assertThat(doc.canRedo()).isTrue();
    }

    @Test
    void redoReappliesWhatWasUndone() {
        PipelineDefinition original = new PipelineDefinition("p", 1, List.of(script("a", List.of())));
        PipelineDocument doc = new PipelineDocument(original);

        doc.apply(PipelineEdits.addStep(script("b", List.of())));
        PipelineDefinition afterAdd = doc.current();
        doc.undo();
        doc.redo();

        assertThat(doc.current()).isEqualTo(afterAdd);
        assertThat(doc.canRedo()).isFalse();
    }

    @Test
    void applyAfterUndoDiscardsTheRedoBranch() {
        PipelineDocument doc = new PipelineDocument(new PipelineDefinition("p", 1, List.of(script("a", List.of()))));
        doc.apply(PipelineEdits.addStep(script("b", List.of())));
        doc.undo();

        doc.apply(PipelineEdits.addStep(script("c", List.of())));

        assertThat(doc.canRedo()).isFalse();
        assertThat(doc.current().steps()).extracting(StepDefinition::id).containsExactly("a", "c");
    }

    @Test
    void aNoOpEditIsNotPushedOntoTheUndoStack() {
        PipelineDocument doc = new PipelineDocument(new PipelineDefinition("p", 1,
                List.of(script("a", List.of()), script("b", List.of("a")))));
        doc.apply(PipelineEdits.addDependency("a", "b")); // already there
        assertThat(doc.canUndo()).isFalse();
    }

    @Test
    void tilePositionsAreIndependentOfUndoRedo() {
        PipelineDocument doc = new PipelineDocument(new PipelineDefinition("p", 1, List.of(script("a", List.of()))));
        doc.setPosition("a", 10, 20);
        doc.apply(PipelineEdits.addStep(script("b", List.of())));
        doc.undo();

        assertThat(doc.position("a")).contains(new TilePosition(10, 20));
    }
}
