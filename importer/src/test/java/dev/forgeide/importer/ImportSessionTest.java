package dev.forgeide.importer;

import dev.forgeide.core.pipeline.AgentStep;
import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.yaml.PipelineTemplates;
import dev.forgeide.core.policy.RetryPolicy;
import dev.forgeide.core.policy.TokenBudget;
import dev.forgeide.importer.scaffold.PromptSection;
import dev.forgeide.importer.scaffold.ScaffoldCatalog;
import dev.forgeide.importer.scaffold.ScaffoldScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImportSessionTest {

    private final Path fixtureRoot = fixture("sample-scaffold");
    private final ScaffoldCatalog catalog = ScaffoldScanner.scan(fixtureRoot);

    @Test
    void forgeliteAgainstTheSampleScaffoldIsFullyMatchedOnConstruction() {
        ImportSession session = new ImportSession(PipelineTemplates.forgelite(), catalog);

        assertThat(session.isComplete()).isTrue();
        assertThat(session.unmatched()).isEmpty();
        assertThat(session.bindings()).hasSize(6);
    }

    @Test
    void resultMapsStepsToTheirSkillRegistryId() {
        ImportSession session = new ImportSession(PipelineTemplates.forgelite(), catalog);

        ImportResult result = session.result();

        assertThat(result.stepToRegistryId())
                .containsEntry("lite-ground", "forgelite")
                .containsEntry("judge-red", "forgelite");
        assertThat(result.registry()).extracting(e -> e.id()).contains("forgelite");
    }

    @Test
    void resultThrowsWhileTilesRemainUnmatched(@TempDir Path tmp) throws IOException {
        AgentStep unknown = new AgentStep("totally-unknown-step", List.of(), "gigacode",
                Path.of("prompts/totally-unknown-step.md"), List.of(), List.of(), List.of(),
                RetryPolicy.DEFAULT, TokenBudget.DEFAULT);
        PipelineDefinition template = new PipelineDefinition("fixture", 1, List.of(unknown));
        ImportSession session = new ImportSession(template, catalog);

        assertThat(session.isComplete()).isFalse();
        assertThatThrownBy(session::result).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void manualBindingResolvesAnUnmatchedTileAndUnblocksResult(@TempDir Path tmp) throws IOException {
        AgentStep unknown = new AgentStep("totally-unknown-step", List.of(), "gigacode",
                Path.of("prompts/totally-unknown-step.md"), List.of(), List.of(), List.of(),
                RetryPolicy.DEFAULT, TokenBudget.DEFAULT);
        PipelineDefinition template = new PipelineDefinition("fixture", 1, List.of(unknown));
        ImportSession session = new ImportSession(template, catalog);
        Path chosen = tmp.resolve("hand-picked-prompt.md");
        Files.writeString(chosen, "Ручками выбранный промпт.", StandardCharsets.UTF_8);

        session.bindManually("totally-unknown-step", chosen);

        assertThat(session.isComplete()).isTrue();
        ImportResult result = session.result();
        assertThat(result.files()).containsEntry(Path.of("prompts/totally-unknown-step.md"), "Ручками выбранный промпт.");
    }

    @Test
    void bindManuallyRejectsUnknownKey(@TempDir Path tmp) throws IOException {
        ImportSession session = new ImportSession(PipelineTemplates.forgelite(), catalog);
        Path chosen = tmp.resolve("x.md");
        Files.writeString(chosen, "x", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> session.bindManually("no-such-key", chosen))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ambiguousBindingBlocksCompletionUntilResolved() {
        PromptSection first = new PromptSection(fixtureRoot.resolve("subagent-prompts.md"), 2,
                "§4.1 dup-step — первое упоминание", Optional.empty(), "первое тело");
        PromptSection second = new PromptSection(fixtureRoot.resolve("subagent-prompts.md"), 2,
                "§9.9 dup-step — второе упоминание", Optional.empty(), "второе тело");
        ScaffoldCatalog ambiguousCatalog = new ScaffoldCatalog(fixtureRoot, Map.of(),
                List.of(first, second), Optional.empty(), List.of(), Optional.empty());
        AgentStep dupStep = new AgentStep("dup-step", List.of(), "gigacode",
                Path.of("prompts/dup-step.md"), List.of(), List.of(), List.of(),
                RetryPolicy.DEFAULT, TokenBudget.DEFAULT);
        PipelineDefinition template = new PipelineDefinition("fixture", 1, List.of(dupStep));
        ImportSession session = new ImportSession(template, ambiguousCatalog);

        assertThat(session.isComplete()).isFalse();
        assertThat(session.ambiguous()).hasSize(1);
        assertThat(session.ambiguous().get(0).candidates()).containsExactlyInAnyOrder(first, second);
        assertThatThrownBy(session::result).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void resolveAmbiguousPicksOneCandidateAndCompletesTheBinding() {
        PromptSection first = new PromptSection(fixtureRoot.resolve("subagent-prompts.md"), 2,
                "§4.1 dup-step — первое упоминание", Optional.empty(), "первое тело");
        PromptSection second = new PromptSection(fixtureRoot.resolve("subagent-prompts.md"), 2,
                "§9.9 dup-step — второе упоминание", Optional.empty(), "второе тело");
        ScaffoldCatalog ambiguousCatalog = new ScaffoldCatalog(fixtureRoot, Map.of(),
                List.of(first, second), Optional.empty(), List.of(), Optional.empty());
        AgentStep dupStep = new AgentStep("dup-step", List.of(), "gigacode",
                Path.of("prompts/dup-step.md"), List.of(), List.of(), List.of(),
                RetryPolicy.DEFAULT, TokenBudget.DEFAULT);
        PipelineDefinition template = new PipelineDefinition("fixture", 1, List.of(dupStep));
        ImportSession session = new ImportSession(template, ambiguousCatalog);

        session.resolveAmbiguous("dup-step", second);

        assertThat(session.isComplete()).isTrue();
        ImportResult result = session.result();
        assertThat(result.files()).containsEntry(Path.of("prompts/dup-step.md"), "второе тело");
    }

    @Test
    void resolveAmbiguousRejectsACandidateThatWasNotOffered() {
        PromptSection first = new PromptSection(fixtureRoot.resolve("subagent-prompts.md"), 2,
                "§4.1 dup-step — первое упоминание", Optional.empty(), "первое тело");
        PromptSection second = new PromptSection(fixtureRoot.resolve("subagent-prompts.md"), 2,
                "§9.9 dup-step — второе упоминание", Optional.empty(), "второе тело");
        PromptSection notOffered = new PromptSection(fixtureRoot.resolve("subagent-prompts.md"), 2,
                "§1.1 совсем другая секция", Optional.empty(), "чужое тело");
        ScaffoldCatalog ambiguousCatalog = new ScaffoldCatalog(fixtureRoot, Map.of(),
                List.of(first, second), Optional.empty(), List.of(), Optional.empty());
        AgentStep dupStep = new AgentStep("dup-step", List.of(), "gigacode",
                Path.of("prompts/dup-step.md"), List.of(), List.of(), List.of(),
                RetryPolicy.DEFAULT, TokenBudget.DEFAULT);
        PipelineDefinition template = new PipelineDefinition("fixture", 1, List.of(dupStep));
        ImportSession session = new ImportSession(template, ambiguousCatalog);

        assertThatThrownBy(() -> session.resolveAmbiguous("dup-step", notOffered))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sectionsOfSplitsAMarkdownFileWithHeadingsAndIsEmptyOtherwise(@TempDir Path tmp) throws IOException {
        Path markdown = tmp.resolve("other-subagent-prompts.md");
        Files.writeString(markdown, "## §1.1 alpha\n\nтело alpha\n\n## §1.2 beta\n\nтело beta\n",
                StandardCharsets.UTF_8);
        Path notMarkdown = tmp.resolve("check_something.py");
        Files.writeString(notMarkdown, "# not actually a heading\nprint('hi')\n", StandardCharsets.UTF_8);

        List<PromptSection> sections = ImportSession.sectionsOf(markdown);

        assertThat(sections).extracting(PromptSection::heading)
                .containsExactly("§1.1 alpha", "§1.2 beta");
        assertThat(sections.get(0).body()).isEqualTo("тело alpha");
        assertThat(ImportSession.sectionsOf(notMarkdown)).isEmpty();
    }

    @Test
    void bindManuallyToSectionUsesOnlyTheSectionBodyNotTheWholeFile(@TempDir Path tmp) throws IOException {
        AgentStep unknown = new AgentStep("totally-unknown-step", List.of(), "gigacode",
                Path.of("prompts/totally-unknown-step.md"), List.of(), List.of(), List.of(),
                RetryPolicy.DEFAULT, TokenBudget.DEFAULT);
        PipelineDefinition template = new PipelineDefinition("fixture", 1, List.of(unknown));
        ImportSession session = new ImportSession(template, catalog);
        Path markdown = tmp.resolve("other-subagent-prompts.md");
        Files.writeString(markdown, "## §1.1 alpha\n\nтело alpha\n\n## §1.2 beta\n\nтело beta\n",
                StandardCharsets.UTF_8);
        List<PromptSection> sections = ImportSession.sectionsOf(markdown);
        PromptSection beta = sections.get(1);

        session.bindManuallyToSection("totally-unknown-step", beta);

        assertThat(session.isComplete()).isTrue();
        ImportResult result = session.result();
        assertThat(result.files()).containsEntry(Path.of("prompts/totally-unknown-step.md"), "тело beta");
    }

    private static Path fixture(String name) {
        try {
            return Paths.get(ImportSessionTest.class
                    .getResource("/dev/forgeide/importer/fixtures/" + name).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
