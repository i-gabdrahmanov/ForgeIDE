package dev.forgeide.ui.importer;

import dev.forgeide.importer.ImportWriter;

import java.util.List;

/**
 * Pure string formatting for the T35 re-import conflict dialog — split out so it is unit-testable
 * without a display (same convention as {@link ImportRowPresentation}).
 */
public final class ImportConflictPresentation {

    private ImportConflictPresentation() {
    }

    public static String rowText(ImportWriter.FileDiff diff) {
        return diff.relativePath().toString();
    }

    /** The dialog's headline, e.g. "Конфликтующих файлов: 2". */
    public static String summary(List<ImportWriter.FileDiff> conflicts) {
        return "Конфликтующих файлов: " + conflicts.size();
    }
}
