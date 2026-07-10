package dev.forgeide.ui.editor;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T20/FR-2.8/FR-8.1: finds the "contract" fenced JSON block (SDD §5.2 — {@code step_id/status/
 * artifacts/pending_questions/summary}) inside an agent-tile prompt, so the editor can highlight
 * it and warn before a save that drops it. Pure text logic — no JavaFX — so it is unit-testable
 * without a display, same convention as {@code StepDetailFields}.
 *
 * <p>Heuristic: the <em>last</em> fenced {@code ```json ... ```} block that contains {@code
 * "step_id"} — a prompt may legitimately show smaller example JSON snippets earlier for
 * illustration, but the actual contract the model must emit is the one closest to the end.
 */
public final class ContractBlockLocator {

    private static final Pattern JSON_FENCE =
            Pattern.compile("```json\\s*?\\n(.*?)```", Pattern.DOTALL);

    private ContractBlockLocator() {
    }

    /** Half-open {@code [start, end)} character offsets into the prompt text, fence markers
     * included, so a caller can style or replace the whole block. */
    public record TextRange(int start, int end) {
        public TextRange {
            if (start < 0 || end < start) {
                throw new IllegalArgumentException("invalid range [" + start + ", " + end + ")");
            }
        }
    }

    public static Optional<TextRange> locate(String promptText) {
        Matcher matcher = JSON_FENCE.matcher(promptText);
        TextRange found = null;
        while (matcher.find()) {
            if (matcher.group(1).contains("\"step_id\"")) {
                found = new TextRange(matcher.start(), matcher.end());
            }
        }
        return Optional.ofNullable(found);
    }

    /** Whether {@code newText} still contains a contract block equivalent to the one located in
     * {@code oldText} — used by the editor's save path to warn before a contract-dropping edit
     * goes through (FR-2.8: "защищена от случайного удаления"). */
    public static boolean contractSurvives(String oldText, String newText) {
        return locate(oldText).isEmpty() || locate(newText).isPresent();
    }
}
