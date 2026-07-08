package dev.forgeide.core.engine;

import java.util.List;

/**
 * Minimal line-based diff summary for {@link PipelineEngine}'s retry-time prompt-drift warning
 * (SDD FR-3.5 traceability row T-12) — not a full unified diff, just enough for a human reading
 * the timeline to see what changed on disk since the run's own snapshot was taken.
 */
final class PromptDiff {

    private static final int MAX_LINES_SHOWN = 5;
    private static final int MAX_LINE_LENGTH = 200;

    private PromptDiff() {
    }

    static String summarize(String before, String after) {
        if (before.equals(after)) {
            return "";
        }
        List<String> a = List.of(before.split("\n", -1));
        List<String> b = List.of(after.split("\n", -1));

        int prefix = 0;
        while (prefix < a.size() && prefix < b.size() && a.get(prefix).equals(b.get(prefix))) {
            prefix++;
        }
        int endA = a.size();
        int endB = b.size();
        while (endA > prefix && endB > prefix && a.get(endA - 1).equals(b.get(endB - 1))) {
            endA--;
            endB--;
        }
        List<String> removed = a.subList(prefix, endA);
        List<String> added = b.subList(prefix, endB);

        StringBuilder sb = new StringBuilder();
        sb.append("from line ").append(prefix + 1).append(": -").append(removed.size())
                .append("/+").append(added.size()).append(" line(s)\n");
        removed.stream().limit(MAX_LINES_SHOWN).forEach(l -> sb.append("- ").append(truncate(l)).append('\n'));
        added.stream().limit(MAX_LINES_SHOWN).forEach(l -> sb.append("+ ").append(truncate(l)).append('\n'));
        return sb.toString();
    }

    private static String truncate(String line) {
        return line.length() > MAX_LINE_LENGTH ? line.substring(0, MAX_LINE_LENGTH) + "…" : line;
    }
}
