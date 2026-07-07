package dev.forgeide.core.pipeline;

import dev.forgeide.core.policy.FailPolicy;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StepDefinitionTest {

    @Test
    void gateStepRequiresAtLeastOneOption() {
        assertThatThrownBy(() -> new GateStep("g", List.of(), "ok?", List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void branchStepRequiresAtLeastOneRoute() {
        assertThatThrownBy(() -> new BranchStep("b", List.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void outwardStepRequiresAtLeastOneAction() {
        assertThatThrownBy(() -> new OutwardStep("o", List.of(), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void scriptStepRequiresNonEmptyCommand() {
        assertThatThrownBy(() -> new ScriptStep("s", List.of(), List.of(), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void perTaskLoopRequiresNonEmptyTemplate() {
        assertThatThrownBy(() -> new PerTaskLoop("p", List.of(), Path.of("task-plan.json"), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void judgeStepRequiresAtLeastOneCheck() {
        assertThatThrownBy(() -> new JudgeStep("j", List.of(), "target",
                Optional.empty(), Optional.empty(), FailPolicy.DEFAULT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sealedHierarchyIsExhaustivelySwitchable() {
        StepDefinition step = new ScriptStep("s", List.of(), List.of("true"), Duration.ofSeconds(1));

        String kind = switch (step) {
            case AgentStep ignored -> "agent";
            case ScriptStep ignored -> "script";
            case JudgeStep ignored -> "judge";
            case GateStep ignored -> "gate";
            case BranchStep ignored -> "branch";
            case PerTaskLoop ignored -> "per_task_loop";
            case OutwardStep ignored -> "outward";
        };

        assertThat(kind).isEqualTo("script");
    }
}
