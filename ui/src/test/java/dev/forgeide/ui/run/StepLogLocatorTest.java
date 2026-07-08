package dev.forgeide.ui.run;

import dev.forgeide.core.pipeline.AgentStep;
import dev.forgeide.core.pipeline.GateStep;
import dev.forgeide.core.pipeline.JudgeStep;
import dev.forgeide.core.pipeline.ScriptStep;
import dev.forgeide.core.policy.FailPolicy;
import dev.forgeide.core.policy.RetryPolicy;
import dev.forgeide.core.policy.TokenBudget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class StepLogLocatorTest {

    private static final TokenBudget BUDGET = new TokenBudget(1_000, Duration.ofMinutes(5), 10);

    @Test
    void agentStepLocatesTheDurableLogDirectory(@TempDir Path repo) {
        AgentStep agent = new AgentStep("work", List.of(), "claude", Path.of("prompts/work.md"),
                List.of(), List.of(), List.of(), RetryPolicy.DEFAULT, BUDGET);

        StepLogLocator.StepLogLocation location = StepLogLocator.locate(repo, "feature-x", agent, 2);

        assertThat(location).isInstanceOfSatisfying(StepLogLocator.StepLogLocation.Directory.class,
                dir -> assertThat(dir.dir()).isEqualTo(repo.resolve("ground").resolve("ai-logs")
                        .resolve("feature-x").resolve("iter-2").resolve("work")));
    }

    @Test
    void judgeWithLlmLocatesItsLlmSubdirectory(@TempDir Path repo) {
        AgentStep llm = new AgentStep("review.llm", List.of(), "claude", Path.of("prompts/review.md"),
                List.of(), List.of(), List.of(), RetryPolicy.DEFAULT, BUDGET);
        JudgeStep judge = new JudgeStep("review", List.of(), "work", Optional.of(llm), Optional.empty(), FailPolicy.DEFAULT);

        StepLogLocator.StepLogLocation location = StepLogLocator.locate(repo, "feature-x", judge, 1);

        assertThat(location).isInstanceOfSatisfying(StepLogLocator.StepLogLocation.Directory.class,
                dir -> assertThat(dir.dir()).isEqualTo(repo.resolve("ground").resolve("ai-logs")
                        .resolve("feature-x").resolve("iter-1").resolve("review").resolve("llm")));
    }

    @Test
    void judgeWithOnlyADeterministicCheckHasNoOutput(@TempDir Path repo) {
        ScriptStep check = new ScriptStep("review.check", List.of(), List.of("check.py"), Duration.ofSeconds(5));
        JudgeStep judge = new JudgeStep("review", List.of(), "work", Optional.empty(), Optional.of(check), FailPolicy.DEFAULT);

        StepLogLocator.StepLogLocation location = StepLogLocator.locate(repo, "feature-x", judge, 1);

        assertThat(location).isInstanceOf(StepLogLocator.StepLogLocation.NoOutput.class);
    }

    @Test
    void scriptAndGateStepsHaveNoOutput(@TempDir Path repo) {
        ScriptStep script = new ScriptStep("build", List.of(), List.of("make"), Duration.ofSeconds(5));
        GateStep gate = new GateStep("gate", List.of(), "Ship?", List.of("yes"), List.of());

        assertThat(StepLogLocator.locate(repo, "feature-x", script, 1))
                .isInstanceOf(StepLogLocator.StepLogLocation.NoOutput.class);
        assertThat(StepLogLocator.locate(repo, "feature-x", gate, 1))
                .isInstanceOf(StepLogLocator.StepLogLocation.NoOutput.class);
    }
}
