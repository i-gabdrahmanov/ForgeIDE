package dev.forgeide.importer.scaffold;

import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

class ScaffoldScannerTest {

    private final Path fixtureRoot = fixture("sample-scaffold");

    @Test
    void findsSkillDirectoriesWithAskillMd() {
        ScaffoldCatalog catalog = ScaffoldScanner.scan(fixtureRoot);

        assertThat(catalog.skillDirs()).containsOnlyKeys("forgelite");
        assertThat(catalog.skillDirs().get("forgelite")).isEqualTo(fixtureRoot.resolve("skills/forgelite"));
    }

    @Test
    void splitsEverySubagentPromptsMdItFindsRegardlessOfDepth() {
        ScaffoldCatalog catalog = ScaffoldScanner.scan(fixtureRoot);

        assertThat(catalog.promptSections()).extracting(PromptSection::heading)
                .anyMatch(h -> h.contains("lite-ground"))
                .anyMatch(h -> h.contains("lite-design"))
                .anyMatch(h -> h.contains("lite-red"))
                .anyMatch(h -> h.contains("lite-green"))
                .anyMatch(h -> h.contains("judge-red"))
                .anyMatch(h -> h.contains("judge-coverage"));
    }

    @Test
    void findsHooksFileByName() {
        ScaffoldCatalog catalog = ScaffoldScanner.scan(fixtureRoot);

        assertThat(catalog.hooksFile()).contains(fixtureRoot.resolve("hooks/settings.hooks.json"));
    }

    @Test
    void findsCheckScriptsByFilenamePattern() {
        ScaffoldCatalog catalog = ScaffoldScanner.scan(fixtureRoot);

        assertThat(catalog.checkScripts()).extracting(p -> p.getFileName().toString())
                .containsExactlyInAnyOrder("check_tests_red.py", "check_coverage.py");
    }

    @Test
    void findsRegistryFile() {
        ScaffoldCatalog catalog = ScaffoldScanner.scan(fixtureRoot);

        assertThat(catalog.registryFile()).contains(fixtureRoot.resolve("SKILLS-REGISTRY.md"));
    }

    @Test
    void rejectsNonDirectoryRoot() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> ScaffoldScanner.scan(fixtureRoot.resolve("SKILLS-REGISTRY.md")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Path fixture(String name) {
        try {
            return Paths.get(ScaffoldScannerTest.class
                    .getResource("/dev/forgeide/importer/fixtures/" + name).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
