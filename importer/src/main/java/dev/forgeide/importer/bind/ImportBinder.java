package dev.forgeide.importer.bind;

import dev.forgeide.core.pipeline.AgentStep;
import dev.forgeide.core.pipeline.BranchStep;
import dev.forgeide.core.pipeline.GateStep;
import dev.forgeide.core.pipeline.JudgeStep;
import dev.forgeide.core.pipeline.OutwardStep;
import dev.forgeide.core.pipeline.PerTaskLoop;
import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.ScriptStep;
import dev.forgeide.core.pipeline.StepDefinition;
import dev.forgeide.importer.scaffold.PromptSection;
import dev.forgeide.importer.scaffold.ScaffoldCatalog;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Matches a bundled template's steps against a scanned scaffold (SD §8, FR-9.1): an
 * {@link AgentStep}'s prompt against a {@link PromptSection} whose heading mentions the step id,
 * a {@link JudgeStep}'s deterministic check against a {@code check_*.py} with the same filename
 * the template already names. This is the one deliberately narrow convention T24 commits to —
 * anything a real scaffold names differently comes back {@link TileBinding.Unmatched} for the
 * user to point at manually, rather than the importer guessing harder (SD §8 explicitly rules out
 * parsing free prose into step semantics). T33 tightens the prompt side of this: a step id
 * matching more than one section's heading comes back {@link TileBinding.Ambiguous} instead of
 * silently binding the first hit, and an id that is a standalone token in one heading is
 * preferred over an id that is merely a substring of a longer one in another (SD §8, ревью
 * импортёра 2026-07-11 №2).
 */
public final class ImportBinder {

    private ImportBinder() {
    }

    public static List<TileBinding> bind(PipelineDefinition template, ScaffoldCatalog catalog) {
        List<TileBinding> bindings = new ArrayList<>();
        for (StepDefinition step : template.steps()) {
            bindStep(step, catalog, bindings);
        }
        return bindings;
    }

    private static void bindStep(StepDefinition step, ScaffoldCatalog catalog, List<TileBinding> out) {
        switch (step) {
            case AgentStep a -> out.add(bindPrompt(a.id(), a.id(), a.promptTemplate(), catalog));
            case JudgeStep j -> {
                j.deterministicCheck().ifPresent(check ->
                        out.add(bindScript(j.id() + ".check", check.command(), catalog)));
                j.llmJudge().ifPresent(llm ->
                        out.add(bindPrompt(j.id() + ".llm", j.id(), llm.promptTemplate(), catalog)));
            }
            case PerTaskLoop loop -> loop.template().forEach(nested -> bindStep(nested, catalog, out));
            case ScriptStep ignored -> {
            }
            case GateStep ignored -> {
            }
            case BranchStep ignored -> {
            }
            case OutwardStep ignored -> {
            }
        }
    }

    private static TileBinding bindPrompt(String key, String searchTerm, Path targetPath, ScaffoldCatalog catalog) {
        List<PromptSection> tokenMatches = catalog.promptSections().stream()
                .filter(s -> s.mentionsAsToken(searchTerm))
                .toList();
        // T33: an exact token match (e.g. a heading literally about "fp-red") always wins over a
        // heading that merely contains the id as a substring of something longer (e.g.
        // "fp-red-fix") — only fall back to substring matching when nothing token-matches.
        List<PromptSection> candidates = tokenMatches.isEmpty()
                ? catalog.promptSections().stream().filter(s -> s.mentions(searchTerm)).toList()
                : tokenMatches;

        if (candidates.isEmpty()) {
            return new TileBinding.Unmatched(key, targetPath,
                    "не найден заголовок в subagent-prompts.md, упоминающий '" + searchTerm + "'");
        }
        if (candidates.size() > 1) {
            return new TileBinding.Ambiguous(key, targetPath, candidates);
        }
        PromptSection match = candidates.get(0);
        return new TileBinding.Matched(key, targetPath, match.sourceFile(), match.body());
    }

    private static TileBinding bindScript(String key, List<String> command, ScaffoldCatalog catalog) {
        Path targetPath = Path.of(scriptToken(command));
        String filename = targetPath.getFileName().toString();
        Optional<Path> match = catalog.checkScripts().stream()
                .filter(p -> p.getFileName().toString().equals(filename))
                .findFirst();
        if (match.isPresent()) {
            return new TileBinding.Matched(key, targetPath, match.get(), readString(match.get()));
        }
        return new TileBinding.Unmatched(key, targetPath,
                "не найден скрипт '" + filename + "' среди check_*.py в обвязке");
    }

    private static String scriptToken(List<String> command) {
        return command.stream().filter(t -> t.endsWith(".py")).findFirst()
                .orElseGet(() -> command.get(command.size() - 1));
    }

    /** The skill directory {@code file} lives under, if any — how a matched binding gets
     * attributed back to a {@code SKILLS-REGISTRY.md} entry (T24's validity badge wiring). */
    public static Optional<String> skillIdFor(Path file, ScaffoldCatalog catalog) {
        for (Map.Entry<String, Path> skill : catalog.skillDirs().entrySet()) {
            if (file.startsWith(skill.getValue())) {
                return Optional.of(skill.getKey());
            }
        }
        return Optional.empty();
    }

    private static String readString(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }
}
