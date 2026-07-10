package dev.forgeide.importer.scaffold;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Slices {@code subagent-prompts.md} into one {@link PromptSection} per markdown heading (SD §8).
 * Real Forge repos mix heading levels for this — top-level subagent contracts under {@code ## 4.x}
 * and judges nested under {@code ### 7.x} beneath a {@code ## 7.} container heading — so every
 * heading line, at any level, starts a new section; a section's body runs until the next heading
 * line regardless of level.
 */
public final class SubagentPromptSplitter {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern NUMBER_PREFIX = Pattern.compile("^§?(\\d+(?:\\.\\d+)?[a-zA-Z]?)\\.?\\s+(.*)$");

    private SubagentPromptSplitter() {
    }

    public static List<PromptSection> split(Path sourceFile, String markdown) {
        List<PromptSection> sections = new ArrayList<>();
        String[] lines = markdown.split("\n", -1);

        int level = -1;
        String heading = null;
        Optional<String> number = Optional.empty();
        StringBuilder body = new StringBuilder();

        for (String line : lines) {
            Matcher m = HEADING.matcher(line.stripTrailing());
            if (m.matches()) {
                if (heading != null) {
                    sections.add(new PromptSection(sourceFile, level, heading, number, body.toString().strip()));
                }
                level = m.group(1).length();
                heading = m.group(2).strip();
                number = leadingNumber(heading);
                body = new StringBuilder();
            } else if (heading != null) {
                body.append(line).append('\n');
            }
        }
        if (heading != null) {
            sections.add(new PromptSection(sourceFile, level, heading, number, body.toString().strip()));
        }
        return sections;
    }

    private static Optional<String> leadingNumber(String heading) {
        Matcher m = NUMBER_PREFIX.matcher(heading);
        return m.matches() ? Optional.of(m.group(1)) : Optional.empty();
    }
}
