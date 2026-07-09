package dev.forgeide.core.pipeline.yaml;

import dev.forgeide.core.pipeline.AgentStep;
import dev.forgeide.core.pipeline.GateStep;
import dev.forgeide.core.pipeline.JudgeStep;
import dev.forgeide.core.pipeline.OutwardAction;
import dev.forgeide.core.pipeline.OutwardStep;
import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.ScriptStep;
import dev.forgeide.core.pipeline.validation.InvalidPipelineException;
import dev.forgeide.core.pipeline.validation.PipelineValidator;
import dev.forgeide.core.project.RiskLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class PipelineYamlTest {

    private final PipelineYaml yaml = new PipelineYaml();

    // ---- happy path -------------------------------------------------------------------

    @Test
    void parsesForgeliteTemplate() {
        PipelineDefinition def = PipelineTemplates.forgelite();

        assertThat(def.id()).isEqualTo("forgelite");
        assertThat(def.version()).isEqualTo(1);
        assertThat(def.params()).extracting(p -> p.name()).containsExactly("jira_key", "spec_path");
        assertThat(def.steps()).extracting(s -> s.id())
                .containsExactly("lite-ground", "lite-design", "gate-design", "lite-red",
                        "judge-red", "lite-green", "judge-coverage", "gate-deliver", "deliver");
    }

    @Test
    void mapsSecurityFieldsOnAgentStep() {
        PipelineDefinition def = PipelineTemplates.forgelite();

        AgentStep green = (AgentStep) def.step("lite-green");
        assertThat(green.allowedWrite()).containsExactly("src/**", "build/**");
        assertThat(green.envScope()).isEmpty();
        assertThat(green.budget().tokens()).isEqualTo(4_000_000L);
        assertThat(green.budget().wallClock().toMinutes()).isEqualTo(40);
        assertThat(green.budget().outputMb()).isEqualTo(512L);
        assertThat(green.retry().stream()).isEqualTo(1); // default materialised
    }

    @Test
    void mapsOutwardStepAndActions() {
        PipelineDefinition def = PipelineTemplates.forgelite();

        OutwardStep deliver = (OutwardStep) def.step("deliver");
        assertThat(deliver.actions()).containsExactly(OutwardAction.GIT_PUSH, OutwardAction.CREATE_PR);
        assertThat(deliver.envScope()).containsExactly("GIT_TOKEN");
        assertThat(deliver.retry().script()).isZero(); // default materialised
    }

    @Test
    void judgeGetsTargetInjectedIntoDependsOn() {
        PipelineDefinition def = PipelineTemplates.forgelite();

        JudgeStep judge = (JudgeStep) def.step("judge-red");
        assertThat(judge.targetStepId()).isEqualTo("lite-red");
        assertThat(judge.dependsOn()).contains("lite-red");
        assertThat(judge.deterministicCheck()).isPresent();
    }

    @Test
    void keepsVariableReferencesLiteralUntilRuntime() {
        PipelineDefinition def = PipelineTemplates.forgelite();

        GateStep gate = (GateStep) def.step("gate-design");
        assertThat(gate.showArtifacts()).singleElement()
                .satisfies(p -> assertThat(p.toString()).contains("${feature.slug}"));
    }

    @Test
    void gateDeclaresItsOwnRiskLevel() {
        PipelineDefinition def = PipelineTemplates.forgelite();

        assertThat(((GateStep) def.step("gate-design")).risk()).isEqualTo(RiskLevel.R1);
        assertThat(((GateStep) def.step("gate-deliver")).risk()).isEqualTo(RiskLevel.R2);
    }

    @Test
    void gateWithoutDeclaredRiskDefaultsToR1() {
        PipelineDefinition def = yaml.parse(read("/pipelines/valid/extras.yaml"));

        GateStep gate = (GateStep) def.step("gate-ship");
        assertThat(gate.risk()).isEqualTo(RiskLevel.R1);
    }

    @Test
    void parsesExtrasWithAllStepTypes() {
        PipelineDefinition def = yaml.parse(read("/pipelines/valid/extras.yaml"));

        JudgeStep review = (JudgeStep) def.step("review");
        assertThat(review.llmJudge()).isPresent();
        assertThat(review.deterministicCheck()).isPresent();
        assertThat(review.failPolicy().maxIterations()).isEqualTo(2);

        ScriptStep build = (ScriptStep) def.step("build");
        assertThat(build.retry().script()).isEqualTo(2);
        assertThat(build.retry().stream()).isEqualTo(1); // default materialised
    }

    // ---- round-trip -------------------------------------------------------------------

    @Test
    void roundTripsForgelite() {
        assertRoundTrip(PipelineTemplates.forgeliteYaml());
    }

    @Test
    void roundTripsExtras() {
        assertRoundTrip(read("/pipelines/valid/extras.yaml"));
    }

    private void assertRoundTrip(String source) {
        PipelineDefinition first = yaml.parse(source);
        PipelineDefinition second = yaml.parse(yaml.serialize(first));
        assertThat(second).isEqualTo(first);
    }

    // ---- invalid fixtures -------------------------------------------------------------

    static Stream<Arguments> invalidCases() {
        return Stream.of(
                arguments("cycle.yaml", "a", "depends_on", "cycle"),
                arguments("dangling-depends.yaml", "b", "depends_on", "ghost"),
                arguments("unknown-judge-target.yaml", "j", "target", "ghost"),
                arguments("unknown-branch-route.yaml", "r", "routes", "ghost"),
                arguments("duplicate-id.yaml", "a", "id", "duplicate"),
                arguments("missing-prompt.yaml", "a", "prompt", "prompt"),
                arguments("unknown-type.yaml", "a", "type", "wizard"),
                arguments("gate-no-options.yaml", "g", "options", "option"),
                arguments("gate-unknown-risk.yaml", "g", "risk", "R9"),
                arguments("outward-no-judge.yaml", "d", "depends_on", "judge"),
                arguments("unknown-variable-scope.yaml", "a", "prompt", "scope"),
                arguments("undeclared-param.yaml", "a", "expects", "undeclared param"),
                arguments("missing-version.yaml", "", "version", "version"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCases")
    void invalidPipelineReportsCoordinate(String resource, String stepId, String field, String messagePart) {
        String source = read("/pipelines/invalid/" + resource);

        InvalidPipelineException ex = catchThrowableOfType(InvalidPipelineException.class, () -> yaml.parse(source));

        assertThat(ex).as("expected %s to be rejected", resource).isNotNull();
        assertThat(ex.errors()).as("error with coordinate for %s", resource)
                .anySatisfy(e -> {
                    assertThat(e.stepId()).isEqualTo(stepId);
                    assertThat(e.field()).isEqualTo(field);
                    assertThat(e.message()).contains(messagePart);
                });
    }

    // ---- filesystem-aware prompt check ------------------------------------------------

    @Test
    void reportsMissingPromptFileWhenRootProvided(@TempDir Path root) {
        String source = """
                version: 1
                id: fs
                steps:
                  - id: a
                    type: agent
                    runtime: gigacode
                    prompt: prompts/absent.md
                """;

        InvalidPipelineException ex = catchThrowableOfType(InvalidPipelineException.class,
                () -> yaml.parse(source, PipelineValidator.Options.withRoot(root)));

        assertThat(ex).isNotNull();
        assertThat(ex.errors()).anySatisfy(e -> {
            assertThat(e.stepId()).isEqualTo("a");
            assertThat(e.field()).isEqualTo("prompt");
        });
    }

    @Test
    void acceptsExistingPromptFileWhenRootProvided(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("prompts"));
        Files.writeString(root.resolve("prompts/present.md"), "hi");
        String source = """
                version: 1
                id: fs
                steps:
                  - id: a
                    type: agent
                    runtime: gigacode
                    prompt: prompts/present.md
                """;

        PipelineDefinition def = yaml.parse(source, PipelineValidator.Options.withRoot(root));

        assertThat(def.step("a")).isInstanceOf(AgentStep.class);
    }

    // ---- lenient parsing (T05 canvas) --------------------------------------------------

    @Test
    void lenientParseOfValidYamlHasNoErrors() {
        PipelineYaml.ParseResult result = yaml.parseLenient(PipelineTemplates.forgeliteYaml());

        assertThat(result.isValid()).isTrue();
        assertThat(result.definition()).isPresent();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void lenientParseOfValidatorLevelErrorStillReturnsTheModel() {
        String source = read("/pipelines/invalid/cycle.yaml");

        PipelineYaml.ParseResult result = yaml.parseLenient(source);

        assertThat(result.isValid()).isFalse();
        assertThat(result.definition()).isPresent();
        assertThat(result.definition().get().steps()).extracting(s -> s.id()).containsExactly("a", "b");
        assertThat(result.errors()).anySatisfy(e -> {
            assertThat(e.stepId()).isEqualTo("a");
            assertThat(e.field()).isEqualTo("depends_on");
            assertThat(e.message()).contains("cycle");
        });
    }

    @Test
    void lenientParseOfStructuralErrorHasNoModel() {
        String source = read("/pipelines/invalid/missing-prompt.yaml");

        PipelineYaml.ParseResult result = yaml.parseLenient(source);

        assertThat(result.isValid()).isFalse();
        assertThat(result.definition()).isEmpty();
        assertThat(result.errors()).anySatisfy(e -> assertThat(e.field()).isEqualTo("prompt"));
    }

    @Test
    void lenientParseOfMalformedYamlHasNoModel() {
        PipelineYaml.ParseResult result = yaml.parseLenient("id: [unterminated");

        assertThat(result.isValid()).isFalse();
        assertThat(result.definition()).isEmpty();
        assertThat(result.errors()).anySatisfy(e -> assertThat(e.message()).contains("malformed YAML"));
    }

    private static String read(String resource) {
        try (InputStream in = PipelineYamlTest.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing test resource: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
