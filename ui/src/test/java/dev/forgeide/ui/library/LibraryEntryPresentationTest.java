package dev.forgeide.ui.library;

import dev.forgeide.core.pipeline.library.LibraryTileMetadata;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LibraryEntryPresentationTest {

    private static LibraryTileMetadata metadata(Optional<LocalDate> validUntil, List<String> scope) {
        return new LibraryTileMetadata("id", "Title", "camiah", validUntil, scope, Instant.now());
    }

    @Test
    void rowTextIncludesTitleOwnerAndScopeTags() {
        String row = LibraryEntryPresentation.rowText(metadata(Optional.empty(), List.of("agent", "judge")));
        assertThat(row).contains("Title").contains("camiah").contains("agent, judge");
    }

    @Test
    void rowTextOmitsBracketsWhenNoScopeTags() {
        String row = LibraryEntryPresentation.rowText(metadata(Optional.empty(), List.of()));
        assertThat(row).doesNotContain("[");
    }

    @Test
    void validityTextIsBlankWhenNoExpiryTracked() {
        assertThat(LibraryEntryPresentation.validityText(metadata(Optional.empty(), List.of()), LocalDate.now())).isBlank();
    }

    @Test
    void validityTextFlagsAnExpiredEntry() {
        LibraryTileMetadata meta = metadata(Optional.of(LocalDate.of(2020, 1, 1)), List.of());
        assertThat(LibraryEntryPresentation.validityText(meta, LocalDate.of(2026, 1, 1))).startsWith("expired");
    }

    @Test
    void validityTextReportsAFutureExpiryAsValid() {
        LibraryTileMetadata meta = metadata(Optional.of(LocalDate.of(2030, 1, 1)), List.of());
        assertThat(LibraryEntryPresentation.validityText(meta, LocalDate.of(2026, 1, 1))).startsWith("valid until");
    }
}
