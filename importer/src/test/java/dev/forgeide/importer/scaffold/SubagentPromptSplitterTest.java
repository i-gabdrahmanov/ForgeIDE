package dev.forgeide.importer.scaffold;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SubagentPromptSplitterTest {

    private static final Path SOURCE = Path.of("subagent-prompts.md");

    @Test
    void splitsOnEveryHeadingLevel() {
        String markdown = """
                # Промпты субагентов

                ## Общее правило для всех субагентов

                Каждый субагент пишет итоговый JSON.

                ## §4.1 lite-ground — grounding

                Текст промпта grounding.

                ## §7. Судьи (Judges)

                ### §7.1 judge-red — проверка RED

                Текст промпта судьи.
                """;

        List<PromptSection> sections = SubagentPromptSplitter.split(SOURCE, markdown);

        assertThat(sections).extracting(PromptSection::heading).containsExactly(
                "Промпты субагентов",
                "Общее правило для всех субагентов",
                "§4.1 lite-ground — grounding",
                "§7. Судьи (Judges)",
                "§7.1 judge-red — проверка RED");
        assertThat(sections).allMatch(s -> s.sourceFile().equals(SOURCE));
    }

    @Test
    void bodyRunsUntilNextHeadingOfAnyLevel() {
        String markdown = """
                ## §4.1 lite-ground — grounding

                Первая строка.
                Вторая строка.

                ### Вложенный подраздел

                Текст подраздела не попадает в тело §4.1.
                """;

        List<PromptSection> sections = SubagentPromptSplitter.split(SOURCE, markdown);

        assertThat(sections.get(0).body()).isEqualTo("Первая строка.\nВторая строка.");
        assertThat(sections.get(1).heading()).isEqualTo("Вложенный подраздел");
        assertThat(sections.get(1).body()).isEqualTo("Текст подраздела не попадает в тело §4.1.");
    }

    @Test
    void parsesLeadingNumberIncludingLetterSuffix() {
        List<PromptSection> sections = SubagentPromptSplitter.split(SOURCE, "## §4.0a SDD-писатель\n\nтело\n");

        assertThat(sections.get(0).number()).contains("4.0a");
    }

    @Test
    void headingWithoutLeadingNumberHasNoAnchor() {
        List<PromptSection> sections = SubagentPromptSplitter.split(SOURCE, "## Общее правило\n\nтело\n");

        assertThat(sections.get(0).number()).isEmpty();
    }

    @Test
    void mentionsMatchesStepIdCaseInsensitively() {
        PromptSection section = SubagentPromptSplitter
                .split(SOURCE, "## §4.1 LITE-GROUND — grounding\n\nтело\n")
                .get(0);

        assertThat(section.mentions("lite-ground")).isTrue();
        assertThat(section.mentions("lite-red")).isFalse();
    }
}
