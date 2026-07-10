package dev.forgeide.ui.editor;

import java.nio.file.Path;

/**
 * T20/FR-8.3: every path {@link dev.forgeide.core.pipeline.StepDefinition}/{@code
 * JudgeScriptLocator} deal in is project-root-relative (e.g. {@code .gigacode/hooks/tdd-guard.py}
 * — the same shape {@code git diff -- <path>} wants), but {@link
 * dev.forgeide.core.port.HarnessGuardPort#edit} takes a <em>harness-root-relative</em> path (e.g.
 * {@code hooks/tdd-guard.py} — see {@code DefaultHarnessGuardTest}'s own calls). Forgetting this
 * split writes into a stray {@code <project>/.gigacode/.gigacode/...} instead of the real file —
 * this is the one seam every trusted-path save (idle or live) has to cross correctly.
 */
public final class HarnessPaths {

    public static final Path HARNESS_DIR = Path.of(".gigacode");

    private HarnessPaths() {
    }

    public static boolean isUnderHarness(Path projectRelativePath) {
        return projectRelativePath.startsWith(HARNESS_DIR);
    }

    /** @throws IllegalArgumentException if {@code projectRelativePath} is not under {@link #HARNESS_DIR} —
     *                                    check {@link #isUnderHarness} first. */
    public static String harnessRelative(Path projectRelativePath) {
        return HARNESS_DIR.relativize(projectRelativePath).toString();
    }
}
