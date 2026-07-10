package dev.forgeide.ui.editor;

import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;

/**
 * T20/FR-8.1 "промпт (RichTextFX, markdown-подсветка), скрипт (python)": a {@link CodeArea} with
 * line numbers and a regex-lexer syntax highlight (SD §7/§10: "RegEx-лексеры" — the same choice
 * bundled tools like RichTextFX's own demos make, no external parser dependency). Re-highlights
 * synchronously on every text change; prompt/script files are small enough (SDD budget: single
 * agent-phase prompts, not whole-repo sources) that this never needs the async/debounced variant
 * some RichTextFX demos use for large sources.
 */
public final class PromptCodeArea extends CodeArea {

    public enum Language {MARKDOWN, PYTHON}

    private final Language language;
    private PromptLexer.Range contractBlock;

    public PromptCodeArea(Language language) {
        this.language = language;
        setParagraphGraphicFactory(LineNumberFactory.get(this));
        getStyleClass().add("prompt-code-area");
        getStylesheets().add(PromptCodeArea.class.getResource("editor.css").toExternalForm());
        textProperty().addListener((obs, oldText, newText) -> refreshHighlighting());
    }

    /** Overlay range (from {@link ContractBlockLocator}) to keep visually distinct from the rest
     * of the syntax highlighting — {@code null} clears it. Re-applied on every text change since
     * the range is only valid for the text it was located against. */
    public void setContractBlock(ContractBlockLocator.TextRange range) {
        this.contractBlock = range == null ? null : new PromptLexer.Range(range.start(), range.end());
        refreshHighlighting();
    }

    private void refreshHighlighting() {
        String text = getText();
        StyleSpans<Collection<String>> base = switch (language) {
            case MARKDOWN -> PromptLexer.markdown(text);
            case PYTHON -> PromptLexer.python(text);
        };
        setStyleSpans(0, contractBlock == null ? base : PromptLexer.overlay(base, contractBlock, "contract-block"));
    }

    /** Regex-lexer implementations, kept out of {@link PromptCodeArea} itself so the highlighting
     * logic (pure text-in, style-spans-out) is reachable without instantiating a JavaFX control. */
    static final class PromptLexer {

        private PromptLexer() {
        }

        record Range(int start, int end) {
        }

        private static final String[] PYTHON_KEYWORDS = {
                "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del",
                "elif", "else", "except", "finally", "for", "from", "global", "if", "import", "in",
                "is", "lambda", "None", "nonlocal", "not", "or", "pass", "raise", "return", "True",
                "False", "try", "while", "with", "yield", "self",
        };

        private static final java.util.regex.Pattern PYTHON_PATTERN = java.util.regex.Pattern.compile(
                "(?<STRING>\"\"\"([^\"\\\\]|\\\\.)*\"\"\"|'''([^'\\\\]|\\\\.)*'''"
                        + "|\"([^\"\\\\\\n]|\\\\.)*\"|'([^'\\\\\\n]|\\\\.)*')"
                        + "|(?<COMMENT>#[^\\n]*)"
                        + "|(?<KEYWORD>\\b(" + String.join("|", PYTHON_KEYWORDS) + ")\\b)"
                        + "|(?<NUMBER>\\b\\d+(\\.\\d+)?\\b)");

        static StyleSpans<Collection<String>> python(String text) {
            return highlight(text, PYTHON_PATTERN, PromptLexer::pythonStyleClass);
        }

        private static String pythonStyleClass(Matcher m) {
            if (m.group("STRING") != null) return "py-string";
            if (m.group("COMMENT") != null) return "py-comment";
            if (m.group("KEYWORD") != null) return "py-keyword";
            if (m.group("NUMBER") != null) return "py-number";
            return null;
        }

        private static final java.util.regex.Pattern MARKDOWN_PATTERN = java.util.regex.Pattern.compile(
                "(?<FENCE>```[a-zA-Z]*\\R([\\s\\S]*?)```)"
                        + "|(?<HEADER>(?m)^#{1,6}[^\\n]*$)"
                        + "|(?<BOLD>\\*\\*[^*\\n]+\\*\\*)"
                        + "|(?<EMPHASIS>_[^_\\n]+_)"
                        + "|(?<CODE>`[^`\\n]+`)");

        static StyleSpans<Collection<String>> markdown(String text) {
            return highlight(text, MARKDOWN_PATTERN, PromptLexer::markdownStyleClass);
        }

        private static String markdownStyleClass(Matcher m) {
            if (m.group("FENCE") != null) return "md-fence";
            if (m.group("HEADER") != null) return "md-header";
            if (m.group("BOLD") != null) return "md-bold";
            if (m.group("EMPHASIS") != null) return "md-emphasis";
            if (m.group("CODE") != null) return "md-code";
            return null;
        }

        private static StyleSpans<Collection<String>> highlight(String text, java.util.regex.Pattern pattern,
                                                                  java.util.function.Function<Matcher, String> classOf) {
            Matcher matcher = pattern.matcher(text);
            int lastEnd = 0;
            StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();
            while (matcher.find()) {
                String styleClass = classOf.apply(matcher);
                if (styleClass == null) {
                    continue;
                }
                builder.add(Collections.emptyList(), matcher.start() - lastEnd);
                builder.add(Collections.singleton(styleClass), matcher.end() - matcher.start());
                lastEnd = matcher.end();
            }
            builder.add(Collections.emptyList(), Math.max(0, text.length() - lastEnd));
            return builder.create();
        }

        /** Layers {@code styleClass} on top of {@code base} for {@code [range.start, range.end)},
         * replacing whatever token classes fall inside it — the contract block is meant to read
         * as a single visually distinct region, not additionally syntax-colored. */
        static StyleSpans<Collection<String>> overlay(StyleSpans<Collection<String>> base, Range range, String styleClass) {
            StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();
            int position = 0;
            for (var span : base) {
                int spanStart = position;
                int spanEnd = position + span.getLength();
                int overlapStart = Math.max(spanStart, range.start());
                int overlapEnd = Math.min(spanEnd, range.end());
                if (overlapStart >= overlapEnd) {
                    builder.add(span.getStyle(), span.getLength());
                } else {
                    if (overlapStart > spanStart) {
                        builder.add(span.getStyle(), overlapStart - spanStart);
                    }
                    builder.add(List.of(styleClass), overlapEnd - overlapStart);
                    if (spanEnd > overlapEnd) {
                        builder.add(span.getStyle(), spanEnd - overlapEnd);
                    }
                }
                position = spanEnd;
            }
            return builder.create();
        }
    }
}
