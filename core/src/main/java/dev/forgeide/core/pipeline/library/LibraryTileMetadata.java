package dev.forgeide.core.pipeline.library;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Registry-style bookkeeping for one library entry (FR-2.9: "owner/validity/scope"), the same
 * shape T24's {@code SKILLS-REGISTRY.md} reader will eventually also produce — {@link
 * dev.forgeide.core.pipeline.TileValidity}/{@code TileValidityChecker} is the port both are meant
 * to feed once a real registry-backed checker exists; until then this is display-only in the
 * library browser (T23 scope stops short of wiring it into canvas badges).
 *
 * @param id        directory-safe, unique within one {@link LibraryScope} directory
 * @param title     human label shown in the library browser
 * @param owner     free text — who to ask about this tile
 * @param validUntil empty means "no expiry tracked"
 * @param scope     free-form tags (e.g. step kinds involved, project area) — not to be confused
 *                  with {@link LibraryScope}, which is where the entry lives, not what it's for
 */
public record LibraryTileMetadata(
        String id,
        String title,
        String owner,
        Optional<LocalDate> validUntil,
        List<String> scope,
        Instant savedAt
) {

    public LibraryTileMetadata {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(validUntil, "validUntil");
        Objects.requireNonNull(savedAt, "savedAt");
        if (id.isBlank()) {
            throw new IllegalArgumentException("library entry id must not be blank");
        }
        scope = List.copyOf(scope);
    }

    public boolean isStale(LocalDate today) {
        return validUntil.isPresent() && validUntil.get().isBefore(today);
    }
}
