package dev.forgeide.core.pipeline.edit;

/**
 * The palette entries of the T22 constructor (SDD FR-2.2/FR-2.5) — one per {@link
 * dev.forgeide.core.pipeline.StepDefinition} subtype. {@link #key()} is the same string
 * {@code pipeline.yaml}'s {@code type:} field and the canvas's {@code tile-<key>} CSS class use
 * (dev.forgeide.ui.canvas.StepTileStyles#typeKey), reused here as the id-prefix a freshly
 * dropped tile gets (see {@link StepIds}) so a fresh id already hints at its type.
 */
public enum StepKind {
    AGENT("agent"),
    SCRIPT("script"),
    JUDGE("judge"),
    GATE("gate"),
    BRANCH("branch"),
    PER_TASK_LOOP("per_task_loop"),
    OUTWARD("outward");

    private final String key;

    StepKind(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public String idPrefix() {
        return key;
    }
}
