package dev.forgeide.ui.project;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class HarnessStatusTextTest {

    @Test
    void summaryForNeverDeployedHasNoBaselineSuffix() {
        assertThat(HarnessStatusText.summary(false, Optional.empty())).isEqualTo("preflight FAILED");
    }

    @Test
    void summaryForAPassingDeployIncludesTheBaselineTimestamp() {
        Instant at = Instant.parse("2026-07-12T21:30:00Z");
        assertThat(HarnessStatusText.summary(true, Optional.of(at)))
                .isEqualTo("deployed, preflight passed (baseline 2026-07-12T21:30:00Z)");
    }

    @Test
    void summaryForAFailingDeployStillShowsTheBaselineOfTheAttempt() {
        Instant at = Instant.parse("2026-07-12T21:30:00Z");
        assertThat(HarnessStatusText.summary(false, Optional.of(at)))
                .isEqualTo("preflight FAILED (baseline 2026-07-12T21:30:00Z)");
    }

    @Test
    void problemsIsEmptyWhenPassed() {
        assertThat(HarnessStatusText.problems(true, "missing .gigacode/settings.hooks.json")).isEmpty();
    }

    @Test
    void problemsSplitsPreflightOutputIntoOneEntryPerLine() {
        String output = "missing hook: hooks/does-not-exist.py\nsettings.hooks.json is not valid JSON: ...\n";
        assertThat(HarnessStatusText.problems(false, output)).containsExactly(
                "missing hook: hooks/does-not-exist.py",
                "settings.hooks.json is not valid JSON: ...");
    }

    @Test
    void problemsIsEmptyForBlankOrNullDetail() {
        assertThat(HarnessStatusText.problems(false, "")).isEmpty();
        assertThat(HarnessStatusText.problems(false, "   ")).isEmpty();
        assertThat(HarnessStatusText.problems(false, null)).isEmpty();
    }

    /** T41: a forge harness's own preflight.py prints a JSON payload — render its errors, then
     * init_needed and warnings (prefixed), instead of dumping raw JSON as bullets. */
    @Test
    void problemsRendersForgePreflightJsonPayload() {
        String json = """
                {"passed": false,
                 "errors": [".gigacode/hooks/settings.hooks.json not found — хуки не задеплоены"],
                 "init_needed": ["ground/pipeline.json not found"],
                 "warnings": ["Python 3.9: пайплайн требует 3.10+"]}
                """;
        assertThat(HarnessStatusText.problems(false, json)).containsExactly(
                ".gigacode/hooks/settings.hooks.json not found — хуки не задеплоены",
                "init: ground/pipeline.json not found",
                "warning: Python 3.9: пайплайн требует 3.10+");
    }
}
