package dev.forgeide.ui.importer;

import dev.forgeide.importer.bind.TileBinding;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ImportRowPresentationTest {

    private final TileBinding matched = new TileBinding.Matched("lite-ground",
            Path.of("prompts/lite-ground.md"), Path.of("skills/forgelite/references/subagent-prompts.md"), "текст");
    private final TileBinding unmatched = new TileBinding.Unmatched("lite-green",
            Path.of("prompts/lite-green.md"), "не найден заголовок");

    @Test
    void isMatchedDistinguishesTheTwoBindingKinds() {
        assertThat(ImportRowPresentation.isMatched(matched)).isTrue();
        assertThat(ImportRowPresentation.isMatched(unmatched)).isFalse();
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
    void summaryCountsMatchedBindings() {
        assertThat(ImportRowPresentation.summary(List.of(matched, unmatched))).isEqualTo("1 из 2 плиток привязано");
    }
}
