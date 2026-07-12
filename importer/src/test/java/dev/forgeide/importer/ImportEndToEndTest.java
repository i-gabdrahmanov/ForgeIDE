package dev.forgeide.importer;

import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.TileValidityStatus;
import dev.forgeide.core.pipeline.validation.PipelineValidator;
import dev.forgeide.core.pipeline.yaml.PipelineTemplates;
import dev.forgeide.core.pipeline.yaml.PipelineYaml;
import dev.forgeide.importer.registry.RegistryTileValidityChecker;
import dev.forgeide.importer.scaffold.ScaffoldCatalog;
import dev.forgeide.importer.scaffold.ScaffoldScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T24 acceptance: "импорт реального forge-репо даёт валидный forgelite-пайплайн: контракты §4.x
 * нарезаны в prompt-файлы, check-скрипты привязаны" — end to end, from a scanned scaffold on disk
 * to a written {@code pipeline.yaml} + {@code prompts/} that a fresh {@link PipelineYaml} load
 * validates cleanly, plus the registry mapping a real {@code RegistryTileValidityChecker} badges
 * tiles with.
 */
class ImportEndToEndTest {

    @Test
    void importingForgeliteAgainstTheSampleScaffoldProducesAValidPipeline(@TempDir Path projectRoot) throws java.io.IOException {
        Path scaffoldRoot = fixture("sample-scaffold");
        ScaffoldCatalog catalog = ScaffoldScanner.scan(scaffoldRoot);
        ImportSession session = new ImportSession(PipelineTemplates.forgelite(), catalog);
        assertThat(session.isComplete()).as("sample scaffold matches every forgelite tile out of the box").isTrue();

        ImportResult result = session.result();
        ImportWriter.write(projectRoot, result);

        Path pipelineFile = projectRoot.resolve(".forgeide/pipeline.yaml");
        assertThat(pipelineFile).isRegularFile();
        String pipelineYaml = Files.readString(pipelineFile);
        PipelineYaml.ParseResult parsed = new PipelineYaml().parseLenient(pipelineYaml,
                PipelineValidator.Options.withRoot(projectRoot));
        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.definition()).isPresent();

        assertThat(projectRoot.resolve("prompts/lite-ground.md")).isRegularFile();
        assertThat(projectRoot.resolve("prompts/lite-design.md")).isRegularFile();
        assertThat(projectRoot.resolve("prompts/lite-red.md")).isRegularFile();
        assertThat(projectRoot.resolve("prompts/lite-green.md")).isRegularFile();
        assertThat(projectRoot.resolve(".gigacode/skills/forgelite/scripts/check_tests_red.py")).isRegularFile();
        assertThat(projectRoot.resolve(".gigacode/skills/forgelite/scripts/check_coverage.py")).isRegularFile();
        // T32: settings.hooks.json must land at the harness root, where preflight.py and the
        // hash-manifest look for it — not under hooks/ (the old, broken location).
        assertThat(projectRoot.resolve(".gigacode/settings.hooks.json")).isRegularFile();
        assertThat(projectRoot.resolve(".gigacode/hooks/settings.hooks.json")).doesNotExist();
        assertThat(projectRoot.resolve(".gigacode/SKILLS-REGISTRY.md")).isRegularFile();
        assertThat(projectRoot.resolve(".forgeide/import-manifest.json")).isRegularFile();

        // T27/SD §6.2: raw agent logs are untrusted and never masked, so the harness deploy
        // itself must keep them out of the target project's git history.
        assertThat(projectRoot.resolve(".gitignore")).isRegularFile();
        assertThat(Files.readString(projectRoot.resolve(".gitignore")).lines())
                .anyMatch(line -> line.strip().equals("ground/ai-logs/"));
    }

    /** T34 acceptance: {@code .gigacode/skills/forgelite/} matches the source directory file for
     * file (minus SKIP_DIRS noise), and a {@code SKILL.md} referencing {@code references/...}
     * resolves against what actually landed on disk in the target project. */
    @Test
    void importCopiesTheWholeSkillDirectoryAndSkillMdReferencesResolve(@TempDir Path projectRoot) throws java.io.IOException {
        Path scaffoldRoot = fixture("sample-scaffold");
        ScaffoldCatalog catalog = ScaffoldScanner.scan(scaffoldRoot);
        ImportResult result = new ImportSession(PipelineTemplates.forgelite(), catalog).result();
        ImportWriter.write(projectRoot, result);

        Path sourceSkillDir = scaffoldRoot.resolve("skills/forgelite");
        Path targetSkillDir = projectRoot.resolve(".gigacode/skills/forgelite");

        assertThat(targetSkillDir.resolve("references/subagent-prompts.md"))
                .hasSameTextualContentAs(sourceSkillDir.resolve("references/subagent-prompts.md"));
        assertThat(targetSkillDir.resolve("scripts/check_tests_red.py"))
                .hasSameTextualContentAs(sourceSkillDir.resolve("scripts/check_tests_red.py"));
        assertThat(targetSkillDir.resolve("scripts/check_coverage.py"))
                .hasSameTextualContentAs(sourceSkillDir.resolve("scripts/check_coverage.py"));
        // SKIP_DIRS noise (same list ScaffoldScanner itself filters) must never ride along.
        assertThat(targetSkillDir.resolve("__pycache__")).doesNotExist();

        String skillMd = Files.readString(targetSkillDir.resolve("SKILL.md"));
        assertThat(skillMd).contains("references/subagent-prompts.md");
        assertThat(targetSkillDir.resolve("references/subagent-prompts.md")).isRegularFile();
    }

    @Test
    void deployAppendsTheAiLogsEntryToAnExistingGitignoreWithoutDuplicatingOnReimport(
            @TempDir Path projectRoot) throws java.io.IOException {
        Files.writeString(projectRoot.resolve(".gitignore"), "build/\nnode_modules/"); // no trailing newline
        Path scaffoldRoot = fixture("sample-scaffold");
        ScaffoldCatalog catalog = ScaffoldScanner.scan(scaffoldRoot);
        ImportResult result = new ImportSession(PipelineTemplates.forgelite(), catalog).result();

        ImportWriter.write(projectRoot, result);
        ImportWriter.write(projectRoot, result); // re-import must not duplicate the entry

        List<String> gitignoreLines = Files.readString(projectRoot.resolve(".gitignore")).lines().toList();
        assertThat(gitignoreLines).contains("build/", "node_modules/", "ground/ai-logs/");
        assertThat(gitignoreLines.stream().filter(l -> l.strip().equals("ground/ai-logs/")).count()).isEqualTo(1);
    }

    @Test
    void deployLeavesAnAlreadyIgnoredEntryAlone(@TempDir Path projectRoot) throws java.io.IOException {
        Files.writeString(projectRoot.resolve(".gitignore"), "/ground/ai-logs\n");
        Path scaffoldRoot = fixture("sample-scaffold");
        ScaffoldCatalog catalog = ScaffoldScanner.scan(scaffoldRoot);
        ImportResult result = new ImportSession(PipelineTemplates.forgelite(), catalog).result();

        ImportWriter.write(projectRoot, result);

        assertThat(Files.readString(projectRoot.resolve(".gitignore"))).isEqualTo("/ground/ai-logs\n");
    }

    @Test
    void writtenManifestAndRegistryDriveRealValidityBadges(@TempDir Path projectRoot) throws java.io.IOException {
        Path scaffoldRoot = fixture("sample-scaffold");
        ScaffoldCatalog catalog = ScaffoldScanner.scan(scaffoldRoot);
        ImportSession session = new ImportSession(PipelineTemplates.forgelite(), catalog);
        ImportResult result = session.result();
        ImportWriter.write(projectRoot, result);

        ImportManifest manifest = ImportManifest.readIfPresent(
                        ImportManifest.pathUnder(projectRoot.resolve(".forgeide")))
                .orElseThrow();
        String registryText = Files.readString(scaffoldRoot.resolve("SKILLS-REGISTRY.md"));
        RegistryTileValidityChecker checker = new RegistryTileValidityChecker(
                dev.forgeide.importer.registry.SkillsRegistryParser.parse(registryText),
                manifest.stepToRegistryId(), YearMonth.of(2026, 7));

        PipelineDefinition reimported = new PipelineYaml().parse(projectRoot.resolve(".forgeide/pipeline.yaml"));
        assertThat(checker.check(reimported.step("lite-ground")).status()).isEqualTo(TileValidityStatus.FRESH);
    }

    private static Path fixture(String name) {
        try {
            return Paths.get(ImportEndToEndTest.class
                    .getResource("/dev/forgeide/importer/fixtures/" + name).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
