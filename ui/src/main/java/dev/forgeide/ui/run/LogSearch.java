package dev.forgeide.ui.run;

/** Full-text search predicate for the step-log tabs (SDD FR-7.7). */
public final class LogSearch {

    private LogSearch() {
    }

    public static boolean matches(String line, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        return line != null && line.toLowerCase().contains(query.toLowerCase());
    }
}
