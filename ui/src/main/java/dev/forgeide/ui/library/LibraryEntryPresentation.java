package dev.forgeide.ui.library;

import dev.forgeide.core.pipeline.library.LibraryTileMetadata;

import java.time.LocalDate;

/**
 * Pure string formatting for one library entry's row in the T23 library browser (FR-2.9's
 * "owner/validity/scope"), split out so it is unit-testable without a display — same convention
 * as {@code StepTileStyles}/{@code TileErrors} in the canvas package.
 */
public final class LibraryEntryPresentation {

    private LibraryEntryPresentation() {
    }

    /** One list-row line: title, owner, and scope tags if any. */
    public static String rowText(LibraryTileMetadata metadata) {
        StringBuilder sb = new StringBuilder(metadata.title()).append("  —  ").append(metadata.owner());
        if (!metadata.scope().isEmpty()) {
            sb.append("  [").append(String.join(", ", metadata.scope())).append(']');
        }
        return sb.toString();
    }

    /** Validity line shown under the row, or empty when no expiry is tracked at all. */
    public static String validityText(LibraryTileMetadata metadata, LocalDate today) {
        return metadata.validUntil()
                .map(until -> metadata.isStale(today) ? "expired " + until : "valid until " + until)
                .orElse("");
    }
}
