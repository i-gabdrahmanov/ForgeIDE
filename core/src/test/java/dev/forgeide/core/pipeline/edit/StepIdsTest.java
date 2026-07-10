package dev.forgeide.core.pipeline.edit;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StepIdsTest {

    @Test
    void firstIdForAKindIsSuffixedWithOne() {
        assertThat(StepIds.next(StepKind.AGENT, Set.of())).isEqualTo("agent-1");
    }

    @Test
    void skipsIdsAlreadyTaken() {
        assertThat(StepIds.next(StepKind.AGENT, Set.of("agent-1", "agent-2"))).isEqualTo("agent-3");
    }

    @Test
    void gapsAreFilledFromTheLowestFreeNumber() {
        assertThat(StepIds.next(StepKind.AGENT, Set.of("agent-1", "agent-3"))).isEqualTo("agent-2");
    }

    @Test
    void duplicateUsesTheOriginalIdAsItsOwnPrefix() {
        assertThat(StepIds.next("review", Set.of("review-1"))).isEqualTo("review-2");
    }
}
