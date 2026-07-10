package dev.forgeide.importer;

import dev.forgeide.core.port.TileValidityChecker;
import dev.forgeide.importer.registry.RegistryTileValidityChecker;
import dev.forgeide.importer.registry.SkillsRegistryEntry;
import dev.forgeide.importer.registry.SkillsRegistryParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Rebuilds the {@link TileValidityChecker} a previous {@link ImportSession#result()} left behind
 * (T24/FR-2.4): a canvas reopened after an IDE restart has no in-memory {@link ImportSession} to
 * ask, only what {@link ImportWriter} wrote to disk — the manifest plus the registry copy under
 * {@code .gigacode}. Either missing means the project was never imported, so {@link
 * TileValidityChecker#unknown()} is the honest answer, not an error.
 */
public final class ProjectValidityCheckers {

    private static final String REGISTRY_COPY = ".gigacode/SKILLS-REGISTRY.md";

    private ProjectValidityCheckers() {
    }

    public static TileValidityChecker load(Path projectRoot) {
        Optional<ImportManifest> manifest = ImportManifest.readIfPresent(
                ImportManifest.pathUnder(projectRoot.resolve(".forgeide")));
        if (manifest.isEmpty()) {
            return TileValidityChecker.unknown();
        }
        Path registryFile = projectRoot.resolve(REGISTRY_COPY);
        if (!Files.isRegularFile(registryFile)) {
            return TileValidityChecker.unknown();
        }
        List<SkillsRegistryEntry> entries = SkillsRegistryParser.parse(readString(registryFile));
        return RegistryTileValidityChecker.of(entries, manifest.get().stepToRegistryId());
    }

    private static String readString(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }
}
