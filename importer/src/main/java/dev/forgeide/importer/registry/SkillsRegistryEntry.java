package dev.forgeide.importer.registry;

import java.time.YearMonth;
import java.util.Objects;
import java.util.Optional;

/**
 * One row of {@code SKILLS-REGISTRY.md} (SD §8, FR-9.2): "owner/validity/scope/evals" per
 * skill/hook. Real Forge repos track validity to month granularity ({@code "2026-12"}, not a
 * full date) — the registry is reviewed on a cadence, not a specific day.
 */
public record SkillsRegistryEntry(String id, String owner, Optional<YearMonth> validUntil, String scope, String evals) {

    public SkillsRegistryEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(validUntil, "validUntil");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(evals, "evals");
    }

    public boolean isStale(YearMonth today) {
        return validUntil.isPresent() && validUntil.get().isBefore(today);
    }
}
