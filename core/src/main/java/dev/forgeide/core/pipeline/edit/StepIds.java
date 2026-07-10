package dev.forgeide.core.pipeline.edit;

import java.util.Set;

/**
 * Generates the "unique id" FR-2.5 asks for when a tile is dropped from the palette or
 * duplicated: {@code <prefix>-<n>}, {@code n} the smallest positive integer not already taken.
 */
public final class StepIds {

    private StepIds() {
    }

    public static String next(StepKind kind, Set<String> existingIds) {
        return next(kind.idPrefix(), existingIds);
    }

    /** @param baseId used by duplicate (the copied step's own id as the prefix) */
    public static String next(String baseId, Set<String> existingIds) {
        int n = 1;
        String candidate;
        do {
            candidate = baseId + "-" + n;
            n++;
        } while (existingIds.contains(candidate));
        return candidate;
    }
}
