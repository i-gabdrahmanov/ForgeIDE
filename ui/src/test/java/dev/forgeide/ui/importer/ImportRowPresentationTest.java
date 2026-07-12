package dev.forgeide.ui.importer;

import dev.forgeide.importer.bind.TileBinding;
import dev.forgeide.importer.scaffold.PromptSection;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ImportRowPresentationTest {

    private final TileBinding matched = new TileBinding.Matched("lite-ground",
            Path.of("prompts/lite-ground.md"), Path.of("skills/forgelite/references/subagent-prompts.md"), "текст");
    private final TileBinding unmatched = new TileBinding.Unmatched("lite-green",
            Path.of("prompts/lite-green.md"), "не найден заголовок");
    private final TileBinding ambiguous = new TileBinding.Ambiguous("dup-step",
            Path.of("prompts/dup-step.md"), List.of(
                    new PromptSection(Path.of("subagent-prompts.md"), 2, "§4.1 dup-step — первое",
                            Optional.empty(), "первое тело"),
                    new PromptSection(Path.of("subagent-prompts.md"), 2, "§9.9 dup-step — второе",
                            Optional.empty(), "второе тело")));

    @Test
    void isMatchedDistinguishesTheThreeBindingKinds() {
        assertThat(ImportRowPresentation.isMatched(matched)).isTrue();
        assertThat(ImportRowPresentation.isMatched(unmatched)).isFalse();
        assertThat(ImportRowPresentation.isMatched(ambiguous)).isFalse();
    }

    @Test
    void isAmbiguousDistinguishesTheAmbiguousBinding() {
        assertThat(ImportRowPresentation.isAmbiguous(ambiguous)).isTrue();
        assertThat(ImportRowPresentation.isAmbiguous(matched)).isFalse();
        assertThat(ImportRowPresentation.isAmbiguous(unmatched)).isFalse();
    }

    @Test
    void rowTextForMatchedNamesTheSource() {
        assertThat(ImportRowPresentation.rowText(matched))
                .contains("lite-ground").contains("subagent-prompts.md");
    }

    @Test
    void rowTextForUnmatchedNamesTheHint() {
        assertThat(ImportRowPresentation.rowText(unmatched))
                .contains("lite-green").contains("НЕ НАЙДЕНО").contains("не найден заголовок");
    }

    @Test
    void rowTextForAmbiguousNamesEveryCandidateHeading() {
        assertThat(ImportRowPresentation.rowText(ambiguous))
                .contains("dup-step").contains("НЕОДНОЗНАЧНО")
                .contains("§4.1 dup-step — первое").contains("§9.9 dup-step — второе");
    }

    @Test
    void summaryCountsMatchedBindings() {
        assertThat(ImportRowPresentation.summary(List.of(matched, unmatched, ambiguous)))
                .isEqualTo("1 из 3 плиток привязано");
    }
}
