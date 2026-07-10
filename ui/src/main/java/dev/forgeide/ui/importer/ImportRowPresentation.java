package dev.forgeide.ui.importer;

import dev.forgeide.importer.bind.TileBinding;

/**
 * Pure string formatting for one {@link TileBinding} row in the T24 import screen — split out so
 * it is unit-testable without a display (same convention as {@code LibraryEntryPresentation}).
 */
public final class ImportRowPresentation {

    private ImportRowPresentation() {
    }

    public static boolean isMatched(TileBinding binding) {
        return binding instanceof TileBinding.Matched;
    }

    public static String rowText(TileBinding binding) {
        return switch (binding) {
            case TileBinding.Matched m -> m.key() + "  —  найдено: " + m.sourcePath().getFileName();
            case TileBinding.Unmatched u -> u.key() + "  —  НЕ НАЙДЕНО: " + u.hint();
        };
    }

    /** One-line summary for the screen header, e.g. "4 из 6 плиток привязано". */
    public static String summary(java.util.List<TileBinding> bindings) {
        long matched = bindings.stream().filter(ImportRowPresentation::isMatched).count();
        return matched + " из " + bindings.size() + " плиток привязано";
    }
}
