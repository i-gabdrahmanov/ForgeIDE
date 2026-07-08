package dev.forgeide.core.port;

import dev.forgeide.core.pipeline.AgentStep;
import dev.forgeide.core.pipeline.TileValidityStatus;
import dev.forgeide.core.policy.RetryPolicy;
import dev.forgeide.core.policy.TokenBudget;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TileValidityCheckerTest {

    @Test
    void unknownCheckerReportsUnknownForAnyStep() {
        AgentStep step = new AgentStep("a", List.of(), "gigacode", Path.of("prompts/a.md"),
                List.of(), List.of(), List.of(), RetryPolicy.DEFAULT,
                new TokenBudget(1_000, Duration.ofMinutes(1), 1));

        assertThat(TileValidityChecker.unknown().check(step).status()).isEqualTo(TileValidityStatus.UNKNOWN);
    }
}
