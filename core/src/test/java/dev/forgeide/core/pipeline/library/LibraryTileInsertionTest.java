package dev.forgeide.core.pipeline.library;

import dev.forgeide.core.pipeline.AgentStep;
import dev.forgeide.core.pipeline.BranchStep;
import dev.forgeide.core.pipeline.GateStep;
import dev.forgeide.core.pipeline.JudgeStep;
import dev.forgeide.core.pipeline.ScriptStep;
import dev.forgeide.core.pipeline.StepDefinition;
import dev.forgeide.core.policy.FailPolicy;
import dev.forgeide.core.policy.RetryPolicy;
import dev.forgeide.core.policy.TokenBudget;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** T23/FR-2.9: "вставка из библиотеки: копирование промпта/конфига, перегенерация id, перевязка
 * путей на структуру целевого проекта" — the pure rewrite {@link LibraryTileInsertion} does. */
class LibraryTileInsertionTest {

    private static LibraryTileMetadata metadata() {
        return new LibraryTileMetadata("agent-judge-gate", "Agent + judge + gate", "camiah",
                Optional.empty(), List.of(), Instant.now());
    }

    @Test
    void regeneratesIdsAndRewiresInternalDependsOn() {
        AgentStep agent = new AgentStep("lite-green", List.of(), "claude", Path.of("prompts/lite-green.md"),
                List.of(), List.of(), List.of(), RetryPolicy.DEFAULT, TokenBudget.DEFAULT);
        GateStep gateStep = new GateStep("gate-deliver", List.of("lite-green"), "Deliver?", List.of("yes", "no"), List.of());
        LibraryTile tile = new LibraryTile(metadata(), List.of(agent, gateStep), Map.of());

        LibraryTileInsertion.Result result = LibraryTileInsertion.insert(tile, Set.of("lite-green", "gate-1"));

        assertThat(result.steps()).extracting(StepDefinition::id)
                .doesNotContain("lite-green", "gate-deliver") // must not collide with existing ids
                .hasSize(2);
        StepDefinition newAgent = result.steps().get(0);
        StepDefinition newGate = result.steps().get(1);
        assertThat(newGate.dependsOn()).containsExactly(newAgent.id()); // followed the id remap
    }

    @Test
    void dropsDependsOnPointingOutsideTheSavedSubgraph() {
        AgentStep agent = new AgentStep("solo", List.of("external-step"), "claude", Path.of(""),
                List.of(), List.of(), List.of(), RetryPolicy.DEFAULT, TokenBudget.DEFAULT);
        LibraryTile tile = new LibraryTile(metadata(), List.of(agent), Map.of());

        LibraryTileInsertion.Result result = LibraryTileInsertion.insert(tile, Set.of());

        assertThat(result.steps().get(0).dependsOn()).isEmpty();
    }

    @Test
    void relocatesThePromptFileAndCopiesItsContent() {
        AgentStep agent = new AgentStep("lite-green", List.of(), "claude", Path.of("prompts/lite-green.md"),
                List.of(), List.of(), List.of(), RetryPolicy.DEFAULT, TokenBudget.DEFAULT);
        LibraryTile tile = new LibraryTile(metadata(), List.of(agent), Map.of("prompts/lite-green.md", "hello"));

        LibraryTileInsertion.Result result = LibraryTileInsertion.insert(tile, Set.of());

        AgentStep newAgent = (AgentStep) result.steps().get(0);
        Path expectedPath = Path.of("prompts", newAgent.id() + ".md");
        assertThat(newAgent.promptTemplate()).isEqualTo(expectedPath);
        assertThat(result.files()).containsEntry(expectedPath, "hello");
    }

    @Test
    void relocatesJudgeLlmAndCheckScriptsUnderTheNewJudgeId() {
        AgentStep llm = new AgentStep("judge-coverage.llm", List.of(), "claude", Path.of("prompts/judge-llm.md"),
                List.of(), List.of(), List.of(), RetryPolicy.DEFAULT, TokenBudget.DEFAULT);
        ScriptStep check = new ScriptStep("judge-coverage.check", List.of(),
                List.of("python3", ".gigacode/skills/forgelite/scripts/check_coverage.py"), Duration.ofMinutes(5));
        JudgeStep judge = new JudgeStep("judge-coverage", List.of(), "lite-green",
                Optional.of(llm), Optional.of(check), FailPolicy.DEFAULT);
        Map<String, String> files = Map.of(
                "prompts/judge-llm.md", "judge prompt",
                ".gigacode/skills/forgelite/scripts/check_coverage.py", "print('check')");
        LibraryTile tile = new LibraryTile(metadata(), List.of(judge), files);

        LibraryTileInsertion.Result result = LibraryTileInsertion.insert(tile, Set.of());

        JudgeStep newJudge = (JudgeStep) result.steps().get(0);
        String newId = newJudge.id();
        assertThat(newJudge.llmJudge()).isPresent();
        assertThat(newJudge.llmJudge().get().id()).isEqualTo(newId + ".llm");
        assertThat(newJudge.llmJudge().get().promptTemplate()).isEqualTo(Path.of("prompts", newId + ".llm.md"));
        assertThat(newJudge.deterministicCheck()).isPresent();
        List<String> newCommand = newJudge.deterministicCheck().get().command();
        assertThat(newCommand.get(0)).isEqualTo("python3");
        Path expectedScriptPath = Path.of("scripts", newId + ".check", "check_coverage.py");
        assertThat(newCommand.get(1)).isEqualTo(expectedScriptPath.toString());
        assertThat(result.files()).containsEntry(expectedScriptPath, "print('check')");
        // target wasn't part of the saved subgraph -> left as-is (dangling, not healed)
        assertThat(newJudge.targetStepId()).isEqualTo("lite-green");
    }

    @Test
    void judgeTargetInsideTheSubgraphFollowsTheIdRemap() {
        AgentStep agent = new AgentStep("lite-green", List.of(), "claude", Path.of(""),
                List.of(), List.of(), List.of(), RetryPolicy.DEFAULT, TokenBudget.DEFAULT);
        ScriptStep check = new ScriptStep("j.check", List.of(), List.of("true"), Duration.ofMinutes(1));
        JudgeStep judge = new JudgeStep("judge", List.of("lite-green"), "lite-green",
                Optional.empty(), Optional.of(check), FailPolicy.DEFAULT);
        LibraryTile tile = new LibraryTile(metadata(), List.of(agent, judge), Map.of());

        LibraryTileInsertion.Result result = LibraryTileInsertion.insert(tile, Set.of());

        StepDefinition newAgent = result.steps().get(0);
        JudgeStep newJudge = (JudgeStep) result.steps().get(1);
        assertThat(newJudge.targetStepId()).isEqualTo(newAgent.id());
    }

    @Test
    void branchRoutesInsideTheSubgraphFollowTheIdRemap() {
        GateStep gateStep = new GateStep("g", List.of(), "Q?", List.of("a", "b"), List.of());
        BranchStep branch = new BranchStep("br", List.of("g"), Map.of("a", "g", "b", "outside"));
        LibraryTile tile = new LibraryTile(metadata(), List.of(gateStep, branch), Map.of());

        LibraryTileInsertion.Result result = LibraryTileInsertion.insert(tile, Set.of());

        StepDefinition newGate = result.steps().get(0);
        BranchStep newBranch = (BranchStep) result.steps().get(1);
        assertThat(newBranch.routes()).containsEntry("a", newGate.id());
        assertThat(newBranch.routes()).containsEntry("b", "outside"); // external, left as-is
    }

    @Test
    void generatedIdsNeverCollideWithExistingOnesAcrossMultipleSteps() {
        AgentStep a1 = new AgentStep("agent-1", List.of(), "claude", Path.of(""),
                List.of(), List.of(), List.of(), RetryPolicy.DEFAULT, TokenBudget.DEFAULT);
        AgentStep a2 = new AgentStep("agent-2", List.of(), "claude", Path.of(""),
                List.of(), List.of(), List.of(), RetryPolicy.DEFAULT, TokenBudget.DEFAULT);
        LibraryTile tile = new LibraryTile(metadata(), List.of(a1, a2), Map.of());

        LibraryTileInsertion.Result result = LibraryTileInsertion.insert(tile, Set.of("agent-1"));

        List<String> newIds = result.steps().stream().map(StepDefinition::id).toList();
        assertThat(newIds).doesNotHaveDuplicates();
        assertThat(newIds).doesNotContain("agent-1");
    }
}
