package dev.forgeide.ui.editor;

import org.fxmisc.richtext.model.StyleSpan;
import org.fxmisc.richtext.model.StyleSpans;
import org.junit.jupiter.api.Test;

import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PromptCodeArea.PromptLexer} is plain text-in/style-spans-out (no {@code Node}
 * instantiation, {@link StyleSpans} lives in {@code org.fxmisc.richtext.model} and needs no
 * JavaFX toolkit) — testable the same way {@code StepDetailFields} is.
 */
class PromptCodeAreaLexerTest {

    @Test
    void pythonHighlightsKeywordsStringsAndComments() {
        String code = "def check():\n    x = \"ok\"  # comment\n    return True\n";

        StyleSpans<Collection<String>> spans = PromptCodeArea.PromptLexer.python(code);

        assertThat(classesAt(spans, code, "def")).contains("py-keyword");
        assertThat(classesAt(spans, code, "\"ok\"")).contains("py-string");
        assertThat(classesAt(spans, code, "# comment")).contains("py-comment");
        assertThat(classesAt(spans, code, "return")).contains("py-keyword");
        assertThat(classesAt(spans, code, "True")).contains("py-keyword");
        assertThat(spans.length()).isEqualTo(code.length());
    }

    @Test
    void markdownHighlightsHeadersAndFences() {
        String text = "# Title\n\nSome **bold** text and `inline code`.\n\n```json\n{\"a\":1}\n```\n";

        StyleSpans<Collection<String>> spans = PromptCodeArea.PromptLexer.markdown(text);

        assertThat(classesAt(spans, text, "# Title")).contains("md-header");
        assertThat(classesAt(spans, text, "**bold**")).contains("md-bold");
        assertThat(classesAt(spans, text, "`inline code`")).contains("md-code");
        assertThat(classesAt(spans, text, "```json\n{\"a\":1}\n```")).contains("md-fence");
    }

    @Test
    void overlayReplacesTheClassesInsideTheGivenRangeOnly() {
        String code = "def a():\n    return True\n";
        StyleSpans<Collection<String>> base = PromptCodeArea.PromptLexer.python(code);
        int start = code.indexOf("return");
        int end = start + "return".length();

        StyleSpans<Collection<String>> overlaid = PromptCodeArea.PromptLexer.overlay(
                base, new PromptCodeArea.PromptLexer.Range(start, end), "contract-block");

        assertThat(classesAt(overlaid, code, "return")).containsExactly("contract-block");
        assertThat(classesAt(overlaid, code, "def")).contains("py-keyword");
        assertThat(overlaid.length()).isEqualTo(base.length());
    }

    private static Collection<String> classesAt(StyleSpans<Collection<String>> spans, String text, String needle) {
        int offset = text.indexOf(needle);
        assertThat(offset).describedAs("'%s' not found in text", needle).isNotNegative();
        int position = 0;
        for (StyleSpan<Collection<String>> span : spans) {
            int spanEnd = position + span.getLength();
            if (offset >= position && offset < spanEnd) {
                return span.getStyle();
            }
            position = spanEnd;
        }
        throw new AssertionError("offset " + offset + " not covered by any span");
    }
}
