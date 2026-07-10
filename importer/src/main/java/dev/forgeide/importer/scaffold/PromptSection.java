package dev.forgeide.importer.scaffold;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * One markdown-heading-delimited slice of a {@code subagent-prompts.md} contract file (SD §8:
 * "контракты §4.x — режутся по заголовкам в отдельные prompt-файлы"). {@link #number} is the
 * leading numeric token of the heading when present (e.g. {@code "4.1"}, {@code "4.0a"},
 * {@code "7.3"} — real Forge repos use these for both subagent prompts, §4.x, and judges, §7.x);
 * a heading without one (section dividers, prose asides) still becomes a section so nothing is
 * silently dropped, it is just never a match target for the binder.
 *
 * @param sourceFile the {@code subagent-prompts.md} this slice came from — its parent skill
 *                    directory is how {@code ImportBinder} attributes a matched step back to a
 *                    {@code SKILLS-REGISTRY.md} entry
 * @param level      markdown heading level (number of leading {@code #})
 * @param heading    the full heading text, number token included, exactly as written
 * @param number     leading numeric token parsed out of {@code heading}, if any
 * @param body       everything between this heading and the next heading of any level
 */
public record PromptSection(Path sourceFile, int level, String heading, Optional<String> number, String body) {

    public PromptSection {
        Objects.requireNonNull(sourceFile, "sourceFile");
        Objects.requireNonNull(heading, "heading");
        Objects.requireNonNull(number, "number");
        Objects.requireNonNull(body, "body");
    }

    /** Case-insensitive substring match of {@code stepId} against the raw heading text — the
     * one deliberately simple convention the binder relies on (SD §8 explicitly rejects parsing
     * free-form prose into step semantics; this is a literal id-in-title check, not that). */
    public boolean mentions(String stepId) {
        return heading.toLowerCase(java.util.Locale.ROOT).contains(stepId.toLowerCase(java.util.Locale.ROOT));
    }
}
