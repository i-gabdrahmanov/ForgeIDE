package dev.forgeide.core.pipeline.validation;

import dev.forgeide.core.pipeline.AgentStep;
import dev.forgeide.core.pipeline.GateStep;
import dev.forgeide.core.pipeline.OutwardAction;
import dev.forgeide.core.pipeline.OutwardStep;
import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.ScriptStep;
import dev.forgeide.core.pipeline.StepDefinition;
import dev.forgeide.core.pipeline.yaml.PipelineTemplates;
import dev.forgeide.core.policy.RetryPolicy;
import dev.forgeide.core.policy.TokenBudget;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

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
