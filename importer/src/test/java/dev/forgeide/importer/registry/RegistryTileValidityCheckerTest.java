package dev.forgeide.importer.registry;

import dev.forgeide.core.pipeline.AgentStep;
import dev.forgeide.core.pipeline.StepDefinition;
import dev.forgeide.core.pipeline.TileValidityStatus;
import dev.forgeide.core.policy.RetryPolicy;
import dev.forgeide.core.policy.TokenBudget;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RegistryTileValidityCheckerTest {

    private final StepDefinition liteGround = new AgentStep("lite-ground", List.of(), "gigacode",
            Path.of("prompts/lite-ground.md"), List.of(), List.of(), List.of(), RetryPolicy.DEFAULT, TokenBudget.DEFAULT);

    @Test
    void freshWhenValidityIsInTheFuture() {
        SkillsRegistryEntry entry = new SkillsRegistryEntry("forgelite", "@team",
                Optional.of(YearMonth.of(2026, 12)), "lite mode", "check_tests_red.py");
        RegistryTileValidityChecker checker = new RegistryTileValidityChecker(
                List.of(entry), Map.of("lite-ground", "forgelite"), YearMonth.of(2026, 7));

        assertThat(checker.check(liteGround).status()).isEqualTo(TileValidityStatus.FRESH);
        assertThat(checker.check(liteGround).detail()).contains("@team").contains("2026-12");
    }

    @Test
    void staleWhenValidityHasPassed() {
        SkillsRegistryEntry entry = new SkillsRegistryEntry("forgelite", "@team",
                Optional.of(YearMonth.of(2026, 3)), "lite mode", "check_tests_red.py");
        RegistryTileValidityChecker checker = new RegistryTileValidityChecker(
                List.of(entry), Map.of("lite-ground", "forgelite"), YearMonth.of(2026, 7));

        assertThat(checker.check(liteGround).status()).isEqualTo(TileValidityStatus.STALE);
    }

    @Test
    void unknownWhenStepHasNoRegistryMapping() {
        RegistryTileValidityChecker checker = new RegistryTileValidityChecker(
                List.of(), Map.of(), YearMonth.of(2026, 7));

        assertThat(checker.check(liteGround).status()).isEqualTo(TileValidityStatus.UNKNOWN);
    }

    @Test
    void unknownWhenMappedRegistryIdIsMissingFromTheEntries() {
        RegistryTileValidityChecker checker = new RegistryTileValidityChecker(
                List.of(), Map.of("lite-ground", "forgelite"), YearMonth.of(2026, 7));

        assertThat(checker.check(liteGround).status()).isEqualTo(TileValidityStatus.UNKNOWN);
    }
}
