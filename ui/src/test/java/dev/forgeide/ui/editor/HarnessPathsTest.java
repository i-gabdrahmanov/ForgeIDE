package dev.forgeide.ui.editor;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** T20/FR-8.3: regression test for a real bug caught in manual verification — a project-relative
 * harness path (e.g. {@code .gigacode/hooks/tdd-guard.py}) fed straight into {@code
 * HarnessGuardPort#edit} without stripping the {@code .gigacode/} prefix wrote into a stray
 * {@code <project>/.gigacode/.gigacode/...} instead of the real file. */
class HarnessPathsTest {

    @Test
    void detectsAPathUnderTheHarnessDir() {
        assertThat(HarnessPaths.isUnderHarness(Path.of(".gigacode/hooks/tdd-guard.py"))).isTrue();
        assertThat(HarnessPaths.isUnderHarness(Path.of("scripts/check_coverage.py"))).isFalse();
    }

    @Test
    void harnessRelativeStripsTheGigacodePrefix() {
        assertThat(HarnessPaths.harnessRelative(Path.of(".gigacode/hooks/tdd-guard.py")))
                .isEqualTo("hooks/tdd-guard.py");
        assertThat(HarnessPaths.harnessRelative(Path.of(".gigacode/skills/forgelite/scripts/check_tests_red.py")))
                .isEqualTo("skills/forgelite/scripts/check_tests_red.py");
    }
}
