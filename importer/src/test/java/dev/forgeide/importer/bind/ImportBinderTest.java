package dev.forgeide.importer.bind;

import dev.forgeide.core.pipeline.AgentStep;
import dev.forgeide.core.pipeline.JudgeStep;
import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.ScriptStep;
import dev.forgeide.core.pipeline.yaml.PipelineTemplates;
import dev.forgeide.core.policy.FailPolicy;
import dev.forgeide.core.policy.RetryPolicy;
import dev.forgeide.core.policy.TokenBudget;
import dev.forgeide.importer.scaffold.ScaffoldCatalog;
import dev.forgeide.importer.scaffold.ScaffoldScanner;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ImportBinderTest {

    private final Path fixtureRoot = fixture("sample-scaffold");
    private final ScaffoldCatalog catalog = ScaffoldScanner.scan(fixtureRoot);

    @Test
    void everyForgeliteAgentAndJudgeStepMatchesTheSampleScaffold() {
        PipelineDefinition forgelite = PipelineTemplates.forgelite();

        List<TileBinding> bindings = ImportBinder.bind(forgelite, catalog);

        assertThat(bindings).extracting(TileBinding::key).containsExactlyInAnyOrder(
                "lite-ground", "lite-design", "lite-red", "lite-green", "judge-red.check", "judge-coverage.check");
        assertThat(bindings).allMatch(b -> b instanceof TileBinding.Matched);
    }

    @Test
    void matchedPromptContentIsTheSectionBody() {
        PipelineDefinition forgelite = PipelineTemplates.forgelite();

        TileBinding.Matched ground = (TileBinding.Matched) ImportBinder.bind(forgelite, catalog).stream()
                .filter(b -> b.key().equals("lite-ground")).findFirst().orElseThrow();

        assertThat(ground.content()).contains("grounding-агент");
        assertThat(ground.targetPath()).isEqualTo(Path.of("prompts/lite-ground.md"));
    }

    @Test
    void matchedScriptContentIsTheWholeFile() {
        PipelineDefinition forgelite = PipelineTemplates.forgelite();

        TileBinding.Matched judgeRed = (TileBinding.Matched) ImportBinder.bind(forgelite, catalog).stream()
                .filter(b -> b.key().equals("judge-red.check")).findFirst().orElseThrow();

        assertThat(judgeRed.content()).contains("red-judge");
        assertThat(judgeRed.sourcePath()).isEqualTo(fixtureRoot.resolve("skills/forgelite/scripts/check_tests_red.py"));
    }

    @Test
    void stepWithNoMatchingHeadingIsUnmatched() {
        AgentStep unknown = new AgentStep("totally-unknown-step", List.of(), "gigacode",
                Path.of("prompts/totally-unknown-step.md"), List.of(), List.of(), List.of(),
                RetryPolicy.DEFAULT, TokenBudget.DEFAULT);
        PipelineDefinition template = new PipelineDefinition("fixture", 1, List.of(unknown));

        List<TileBinding> bindings = ImportBinder.bind(template, catalog);

        assertThat(bindings).hasSize(1);
        assertThat(bindings.get(0)).isInstanceOf(TileBinding.Unmatched.class);
        assertThat(((TileBinding.Unmatched) bindings.get(0)).hint()).contains("totally-unknown-step");
    }

    @Test
    void judgeCheckWithNoMatchingScriptIsUnmatched() {
        ScriptStep check = new ScriptStep("judge-missing.check", List.of(),
                List.of("python3", ".gigacode/skills/forgelite/scripts/check_does_not_exist.py"), Duration.ofMinutes(5));
        JudgeStep judge = new JudgeStep("judge-missing", List.of("lite-ground"), "lite-ground",
                Optional.empty(), Optional.of(check), FailPolicy.DEFAULT);
        PipelineDefinition template = new PipelineDefinition("fixture", 1, List.of(judge));

        List<TileBinding> bindings = ImportBinder.bind(template, catalog);

        assertThat(bindings).hasSize(1);
        assertThat(bindings.get(0)).isInstanceOf(TileBinding.Unmatched.class);
        assertThat(((TileBinding.Unmatched) bindings.get(0)).hint()).contains("check_does_not_exist.py");
    }

    @Test
    void skillIdIsDerivedFromTheMatchedSourcesSkillDirectory() {
        PipelineDefinition forgelite = PipelineTemplates.forgelite();

        TileBinding.Matched ground = (TileBinding.Matched) ImportBinder.bind(forgelite, catalog).stream()
                .filter(b -> b.key().equals("lite-ground")).findFirst().orElseThrow();

        assertThat(ImportBinder.skillIdFor(ground.sourcePath(), catalog)).contains("forgelite");
    }

    private static Path fixture(String name) {
        try {
            return Paths.get(ImportBinderTest.class
                    .getResource("/dev/forgeide/importer/fixtures/" + name).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
