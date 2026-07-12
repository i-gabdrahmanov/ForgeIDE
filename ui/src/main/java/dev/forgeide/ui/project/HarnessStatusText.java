package dev.forgeide.ui.project;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * T37: pure text-formatting for the project card's harness-status line and its problem list —
 * split out from {@link ProjectDetailView} so it's testable without a JavaFX toolkit (same split
 * as {@link FlagsText}).
 */
final class HarnessStatusText {

    private HarnessStatusText() {
    }

    /** One-line summary next to the "Deploy harness" button. */
    static String summary(boolean passed, Optional<Instant> deployedAt) {
        String baseline = deployedAt.map(at -> " (baseline " + DateTimeFormatter.ISO_INSTANT.format(at) + ")").orElse("");
        return (passed ? "deployed, preflight passed" : "preflight FAILED") + baseline;
    }

    /** {@code preflight.py} prints one problem per line — split it back apart so a failed deploy
     * shows a bullet list (T37 acceptance) instead of one run-on blob. Blank both when passed
     * (nothing to show) and when {@code detail} itself is blank. */
    static List<String> problems(boolean passed, String detail) {
        if (passed || detail == null || detail.isBlank()) {
            return List.of();
        }
        return detail.lines().map(String::strip).filter(line -> !line.isBlank()).toList();
    }
}
