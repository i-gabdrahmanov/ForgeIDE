package dev.forgeide.importer.registry;

import dev.forgeide.core.pipeline.StepDefinition;
import dev.forgeide.core.pipeline.TileValidity;
import dev.forgeide.core.port.TileValidityChecker;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Real {@link TileValidityChecker} backed by a parsed {@code SKILLS-REGISTRY.md} (SD §8, FR-9.2,
 * FR-2.4): the canvas already knows how to badge whatever this returns (T05) — this is the piece
 * that was missing, T24's job. A step maps to a registry entry via {@code stepToRegistryId}
 * (built by {@code ImportBinder} while it matches paths, then persisted in {@code
 * ImportManifest} so the mapping survives a restart); a step with no mapping, or a mapping to an
 * id the registry no longer lists, reports {@link TileValidity#unknown()} rather than guessing.
 */
public final class RegistryTileValidityChecker implements TileValidityChecker {

    private final Map<String, SkillsRegistryEntry> byId;
    private final Map<String, String> stepToRegistryId;
    private final YearMonth today;

    public RegistryTileValidityChecker(List<SkillsRegistryEntry> entries, Map<String, String> stepToRegistryId,
                                        YearMonth today) {
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(stepToRegistryId, "stepToRegistryId");
        this.today = Objects.requireNonNull(today, "today");
        this.byId = entries.stream().collect(java.util.stream.Collectors.toMap(
                SkillsRegistryEntry::id, e -> e, (a, b) -> a, java.util.LinkedHashMap::new));
        this.stepToRegistryId = Map.copyOf(stepToRegistryId);
    }

    /** Convenience for real use — "today" is the wall-clock month. */
    public static RegistryTileValidityChecker of(List<SkillsRegistryEntry> entries, Map<String, String> stepToRegistryId) {
        return new RegistryTileValidityChecker(entries, stepToRegistryId, YearMonth.now());
    }

    @Override
    public TileValidity check(StepDefinition step) {
        String registryId = stepToRegistryId.get(step.id());
        if (registryId == null) {
            return TileValidity.unknown();
        }
        SkillsRegistryEntry entry = byId.get(registryId);
        if (entry == null) {
            return TileValidity.unknown();
        }
        String detail = "owner: " + entry.owner() + entry.validUntil()
                .map(until -> (entry.isStale(today) ? ", expired " : ", valid until ") + until)
                .orElse("");
        return entry.isStale(today) ? TileValidity.stale(detail) : TileValidity.fresh(detail);
    }
}
