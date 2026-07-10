package dev.forgeide.ui.canvas;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure line-based diff (classic LCS) for the T22 YAML tab's "diff before save" (FR-2.7): no
 * JavaFX dependency, so it is unit-testable without a display. {@code pipeline.yaml} files are
 * small, so the O(n*m) table is plenty fast.
 */
public final class TextDiff {

    public enum LineKind {
        CONTEXT, ADDED, REMOVED
    }

    public record Line(LineKind kind, String text) {
    }

    private TextDiff() {
    }

    public static List<Line> diff(String before, String after) {
        String[] a = before.split("\n", -1);
        String[] b = after.split("\n", -1);
        int n = a.length;
        int m = b.length;
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = a[i].equals(b[j]) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }

        List<Line> lines = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (a[i].equals(b[j])) {
                lines.add(new Line(LineKind.CONTEXT, a[i]));
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                lines.add(new Line(LineKind.REMOVED, a[i]));
                i++;
            } else {
                lines.add(new Line(LineKind.ADDED, b[j]));
                j++;
            }
        }
        while (i < n) {
            lines.add(new Line(LineKind.REMOVED, a[i++]));
        }
        while (j < m) {
            lines.add(new Line(LineKind.ADDED, b[j++]));
        }
        return lines;
    }

    /** Unified-diff-flavoured text for a read-only preview area. */
    public static String render(String before, String after) {
        if (before.equals(after)) {
            return "(no changes)";
        }
        StringBuilder sb = new StringBuilder();
        for (Line line : diff(before, after)) {
            String prefix = switch (line.kind()) {
                case CONTEXT -> "  ";
                case ADDED -> "+ ";
                case REMOVED -> "- ";
            };
            sb.append(prefix).append(line.text()).append('\n');
        }
        return sb.toString();
    }
}
