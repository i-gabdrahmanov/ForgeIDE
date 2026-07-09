package dev.forgeide.core.pipeline.validation;

import dev.forgeide.core.pipeline.AgentStep;
import dev.forgeide.core.pipeline.GateStep;
import dev.forgeide.core.pipeline.JudgeStep;
import dev.forgeide.core.pipeline.OutwardAction;
import dev.forgeide.core.pipeline.OutwardStep;
import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.ScriptStep;
import dev.forgeide.core.pipeline.StepDefinition;
import dev.forgeide.core.pipeline.yaml.PipelineTemplates;
import dev.forgeide.core.policy.FailPolicy;
import dev.forgeide.core.policy.RetryPolicy;
import dev.forgeide.core.policy.TokenBudget;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineValidatorTest {

    private final PipelineValidator validator = new PipelineValidator();

    @Test
    void forgeliteHasNoErrors() {
        assertThat(validator.validate(PipelineTemplates.forgelite())).isEmpty();
    }

    @Test
    void returnsErrorsInsteadOfThrowingForLiveValidation() {
        // gate -> outward with no judge upstream: a graph the canvas can build incrementally.
        StepDefinition gate = new GateStep("g", List.of(), "Deliver?", List.of("ok"), List.of());
        StepDefinition outward = new OutwardStep("d", List.of("g"),
                List.of(OutwardAction.GIT_PUSH), List.of("GIT_TOKEN"));
        PipelineDefinition def = new PipelineDefinition("p", 1, List.of(gate, outward));

        List<PipelineError> errors = validator.validate(def);

        assertThat(errors).anySatisfy(e -> {
            assertThat(e.stepId()).isEqualTo("d");
            assertThat(e.message()).contains("judge");
        });
    }

    @Test
    void outwardStepBehindAnLlmOnlyJudgeIsRejected() {
        // FR-6.1: the deterministic recheck is mandatory for a judge that gates an outward step —
        // an LLM verdict alone is advisory and cannot be the sole decider (Т-8).
        StepDefinition work = new ScriptStep("work", List.of(), List.of("echo"), Duration.ofMinutes(1));
        AgentStep llm = new AgentStep("review.llm", List.of(), "claude", Path.of("p/review.md"),
                List.of(), List.of(), List.of(), RetryPolicy.DEFAULT,
                new TokenBudget(1_000, Duration.ofMinutes(5), 64));
        StepDefinition review = new JudgeStep("review", List.of("work"), "work", Optional.of(llm), Optional.empty(),
                FailPolicy.DEFAULT);
        StepDefinition outward = new OutwardStep("d", List.of("review"),
                List.of(OutwardAction.GIT_PUSH), List.of("GIT_TOKEN"));
        PipelineDefinition def = new PipelineDefinition("p", 1, List.of(work, review, outward));

        List<PipelineError> errors = validator.validate(def);

        assertThat(errors).anySatisfy(e -> {
            assertThat(e.stepId()).isEqualTo("d");
            assertThat(e.message()).contains("deterministic");
        });
    }

    @Test
    void outwardStepBehindAJudgeWithDeterministicCheckValidates() {
        StepDefinition work = new ScriptStep("work", List.of(), List.of("echo"), Duration.ofMinutes(1));
        ScriptStep check = new ScriptStep("review.check", List.of(), List.of("check.py"), Duration.ofMinutes(1));
        StepDefinition review = new JudgeStep("review", List.of("work"), "work", Optional.empty(),
                Optional.of(check), FailPolicy.DEFAULT);
        StepDefinition outward = new OutwardStep("d", List.of("review"),
                List.of(OutwardAction.GIT_PUSH), List.of("GIT_TOKEN"));
        PipelineDefinition def = new PipelineDefinition("p", 1, List.of(work, review, outward));

        assertThat(validator.validate(def)).isEmpty();
    }

    @Test
    void flagsUnreachableStep() {
        // 'a' is an entry; 'b' and 'c' depend only on each other -> a cycle, hence unreachable.
        StepDefinition a = new ScriptStep("a", List.of(), List.of("echo"), Duration.ofMinutes(1));
        StepDefinition b = new ScriptStep("b", List.of("c"), List.of("echo"), Duration.ofMinutes(1));
        StepDefinition c = new ScriptStep("c", List.of("b"), List.of("echo"), Duration.ofMinutes(1));
        PipelineDefinition def = new PipelineDefinition("p", 1, List.of(a, b, c));

        List<PipelineError> errors = validator.validate(def);

        assertThat(errors).extracting(PipelineError::stepId).contains("b", "c");
    }

    @Test
    void cleanLinearGraphValidates() {
        StepDefinition ground = new AgentStep("ground", List.of(), "gigacode", Path.of("p/ground.md"),
                List.of(), List.of(), List.of(), RetryPolicy.DEFAULT,
                new TokenBudget(1_000, Duration.ofMinutes(5), 64));
        StepDefinition build = new ScriptStep("build", List.of("ground"), List.of("gradle", "build"),
                Duration.ofMinutes(5));
        PipelineDefinition def = new PipelineDefinition("p", 1, List.of(ground, build));

        assertThat(validator.validate(def)).isEmpty();
    }
}
