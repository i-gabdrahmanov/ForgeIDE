package dev.forgeide.core.pipeline.edit;

import dev.forgeide.core.pipeline.AgentStep;
import dev.forgeide.core.pipeline.StepDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** Every palette default must satisfy its own record's constructor invariants (FR-2.5): a
 * dropped tile must exist on the canvas even though it is still incomplete. */
class StepDefaultsTest {

    @ParameterizedTest
    @EnumSource(StepKind.class)
    void everyKindProducesAConstructibleStepWithTheGivenId(StepKind kind) {
        assertThatCode(() -> StepDefaults.create(kind, "tile-1")).doesNotThrowAnyException();
        StepDefinition step = StepDefaults.create(kind, "tile-1");
        assertThat(step.id()).isEqualTo("tile-1");
        assertThat(step.dependsOn()).isEmpty();
    }

    @Test
    void freshAgentTilePointsAtAScaffoldablePromptPath() {
        // T23/FR-2.8: a real path (not empty) so the caller can seed it with AgentPromptScaffold.
        AgentStep step = (AgentStep) StepDefaults.create(StepKind.AGENT, "lite-ground-1");
        assertThat(step.promptTemplate()).isEqualTo(Path.of("prompts", "lite-ground-1.md"));
    }
}
