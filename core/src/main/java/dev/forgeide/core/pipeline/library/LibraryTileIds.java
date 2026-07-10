package dev.forgeide.core.pipeline.library;

import java.util.Locale;
import java.util.Set;

/** Directory-safe entry ids for {@link TileLibraryStore} (one directory per entry — see its
 * javadoc), derived from the human title the save dialog collects. Same "smallest free suffix"
 * shape as {@code StepIds}, just keyed by a slug instead of a step-kind prefix. */
public final class LibraryTileIds {

    private LibraryTileIds() {
    }

    public static String slug(String title) {
        String base = title.toLowerCase(Locale.ROOT).trim()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return base.isBlank() ? "tile" : base;
    }

    /** {@code slug(title)}, disambiguated against {@code existingIds} by appending {@code -2},
     * {@code -3}, … the same way a fresh canvas tile id is disambiguated. */
    public static String unique(String title, Set<String> existingIds) {
        String base = slug(title);
        if (!existingIds.contains(base)) {
            return base;
        }
        int n = 2;
        while (existingIds.contains(base + "-" + n)) {
            n++;
        }
        return base + "-" + n;
    }
}
