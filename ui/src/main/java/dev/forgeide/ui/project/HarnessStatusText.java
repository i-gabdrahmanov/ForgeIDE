package dev.forgeide.ui.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * T37: pure text-formatting for the project card's harness-status line and its problem list —
 * split out from {@link ProjectDetailView} so it's testable without a JavaFX toolkit (same split
 * as {@link FlagsText}).
 */
final class HarnessStatusText {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HarnessStatusText() {
    }

    /** One-line summary next to the "Deploy harness" button. */
    static String summary(boolean passed, Optional<Instant> deployedAt) {
        String baseline = deployedAt.map(at -> " (baseline " + DateTimeFormatter.ISO_INSTANT.format(at) + ")").orElse("");
        return (passed ? "deployed, preflight passed" : "preflight FAILED") + baseline;
    }

    /** The bundled {@code preflight.py} prints one problem per line; a forge harness's own
     * {@code preflight.py} (T41) prints a JSON object with {@code errors}/{@code init_needed}/
     * {@code warnings} arrays. Render either as a bullet list (T37 acceptance) instead of a run-on
     * blob or raw JSON. Blank both when passed (nothing to show) and when {@code detail} is blank. */
    static List<String> problems(boolean passed, String detail) {
        if (passed || detail == null || detail.isBlank()) {
            return List.of();
        }
        List<String> structured = fromJson(detail);
        return structured != null ? structured
                : detail.lines().map(String::strip).filter(line -> !line.isBlank()).toList();
    }

    /** Parses a forge preflight JSON payload into prefixed bullets, or {@code null} when {@code
     * detail} isn't such a payload (then the caller falls back to line-splitting). */
    private static List<String> fromJson(String detail) {
        JsonNode root;
        try {
            root = MAPPER.readTree(detail);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
        if (root == null || !root.isObject()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        collect(root.get("errors"), "", out);
        collect(root.get("init_needed"), "init: ", out);
        collect(root.get("warnings"), "warning: ", out);
        return out.isEmpty() ? null : out;
    }

    private static void collect(JsonNode array, String prefix, List<String> out) {
        if (array != null && array.isArray()) {
            array.forEach(node -> out.add(prefix + node.asText()));
        }
    }
}
