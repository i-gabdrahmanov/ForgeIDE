package dev.forgeide.importer.registry;

import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SkillsRegistryParserTest {

    private static final String REGISTRY = """
            # Skills Registry

            Some prose before the table.

            | Skill | Owner | Validity | Scope | Evals |
            |---|---|---|---|---|
            | forgelite | @team | 2026-12 | lite mode | check_tests_red.py + check_coverage.py |
            | legacy-skill | @team | 2026-03 | заменён forgelite | — |

            ## Control-plane хуки

            | Hook | Owner | Validity | Назначение | Evals |
            |---|---|---|---|---|
            | gate-guard | @team | 2026-12 | risk ladder | run-evals.py |
            """;

    @Test
    void parsesSkillRows() {
        List<SkillsRegistryEntry> entries = SkillsRegistryParser.parse(REGISTRY);

        assertThat(entries).extracting(SkillsRegistryEntry::id)
                .containsExactly("forgelite", "legacy-skill", "gate-guard");
    }

    @Test
    void parsesValidityAsYearMonth() {
        List<SkillsRegistryEntry> entries = SkillsRegistryParser.parse(REGISTRY);

        SkillsRegistryEntry forgelite = entries.get(0);
        assertThat(forgelite.owner()).isEqualTo("@team");
        assertThat(forgelite.validUntil()).contains(YearMonth.of(2026, 12));
        assertThat(forgelite.scope()).isEqualTo("lite mode");
        assertThat(forgelite.evals()).isEqualTo("check_tests_red.py + check_coverage.py");
    }

    @Test
    void nonDateValidityCellBecomesEmpty() {
        List<SkillsRegistryEntry> entries = SkillsRegistryParser.parse("""
                | Skill | Owner | Validity | Scope | Evals |
                |---|---|---|---|---|
                | router | @team | — | entrypoint | — |
                """);

        assertThat(entries.get(0).validUntil()).isEmpty();
    }

    @Test
    void staleIsBeforeToday() {
        SkillsRegistryEntry entry = SkillsRegistryParser.parse(REGISTRY).get(1); // legacy-skill, 2026-03

        assertThat(entry.isStale(YearMonth.of(2026, 7))).isTrue();
        assertThat(entry.isStale(YearMonth.of(2026, 1))).isFalse();
    }

    @Test
    void secondTableWithHookColumnParsesTheSameShape() {
        List<SkillsRegistryEntry> entries = SkillsRegistryParser.parse(REGISTRY);

        SkillsRegistryEntry hook = entries.get(2);
        assertThat(hook.id()).isEqualTo("gate-guard");
        assertThat(hook.validUntil()).contains(YearMonth.of(2026, 12));
    }

    @Test
    void ignoresUnrelatedPipeTables() {
        List<SkillsRegistryEntry> entries = SkillsRegistryParser.parse("""
                | Column A | Column B |
                |---|---|
                | x | y |
                """);

        assertThat(entries).isEmpty();
    }
}
