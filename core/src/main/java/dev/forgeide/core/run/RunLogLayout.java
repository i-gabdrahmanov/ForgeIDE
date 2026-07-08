package dev.forgeide.core.run;

import java.nio.file.Path;

/**
 * {@code <project>/ground/ai-logs/<feature>/iter-NN/<step>/} (SD §6.2, SDD FR-7.4): where an
 * agent phase's {@code stdout.jsonl}/{@code stderr.log}/{@code meta.json} live. Shared by
 * {@code PipelineEngine} (which passes the directory to the agent runtime port) and the UI's log
 * tailer (T10), so both agree on the layout by construction rather than by copying the formula.
 */
public final class RunLogLayout {

    private RunLogLayout() {
    }

    /** {@code <project>/ground/ai-logs/<feature>/} — every iteration/step for one feature. */
    public static Path featureLogRoot(Path projectRoot, String featureSlug) {
        return projectRoot.resolve("ground").resolve("ai-logs").resolve(featureSlug);
    }

    public static Path stepLogDir(Path projectRoot, String featureSlug, String stepId, int iteration) {
        return featureLogRoot(projectRoot, featureSlug).resolve("iter-" + iteration).resolve(stepId);
    }
}
