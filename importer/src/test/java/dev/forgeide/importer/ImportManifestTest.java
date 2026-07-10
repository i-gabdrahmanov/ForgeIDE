package dev.forgeide.importer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ImportManifestTest {

    @Test
    void writeThenReadRoundTrips(@TempDir Path tmp) {
        ImportManifest manifest = new ImportManifest(Map.of("lite-ground", "forgelite", "judge-red", "forgelite"));
        Path file = tmp.resolve(".forgeide/import-manifest.json");

        manifest.write(file);
        Optional<ImportManifest> read = ImportManifest.readIfPresent(file);

        assertThat(read).contains(manifest);
    }

    @Test
    void readIfPresentIsEmptyWhenFileIsMissing(@TempDir Path tmp) {
        assertThat(ImportManifest.readIfPresent(tmp.resolve("nope.json"))).isEmpty();
    }

    @Test
    void pathUnderIsSiblingOfPipelineYaml(@TempDir Path tmp) {
        Path forgeideDir = tmp.resolve(".forgeide");

        assertThat(ImportManifest.pathUnder(forgeideDir)).isEqualTo(forgeideDir.resolve("import-manifest.json"));
    }
}
