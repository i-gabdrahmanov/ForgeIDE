package dev.forgeide.ui.canvas;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextDiffTest {

    @Test
    void identicalTextHasNoDiffLines() {
        assertThat(TextDiff.diff("a\nb\n", "a\nb\n")).allMatch(l -> l.kind() == TextDiff.LineKind.CONTEXT);
        assertThat(TextDiff.render("a\nb\n", "a\nb\n")).isEqualTo("(no changes)");
    }

    @Test
    void detectsAnAddedLine() {
        var lines = TextDiff.diff("a\nb\n", "a\nb\nc\n");
        assertThat(lines).extracting(TextDiff.Line::kind).contains(TextDiff.LineKind.ADDED);
        assertThat(lines).filteredOn(l -> l.kind() == TextDiff.LineKind.ADDED)
                .extracting(TextDiff.Line::text).containsExactly("c");
    }

    @Test
    void detectsARemovedLine() {
        var lines = TextDiff.diff("a\nb\nc\n", "a\nc\n");
        assertThat(lines).filteredOn(l -> l.kind() == TextDiff.LineKind.REMOVED)
                .extracting(TextDiff.Line::text).containsExactly("b");
    }

    @Test
    void renderPrefixesEachLineByKind() {
        String rendered = TextDiff.render("a\n", "b\n");
        assertThat(rendered).contains("- a").contains("+ b");
    }
}
