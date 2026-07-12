package dev.forgeide.importer;

import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.importer.bind.ImportBinder;
import dev.forgeide.importer.bind.TileBinding;
import dev.forgeide.importer.registry.SkillsRegistryEntry;
import dev.forgeide.importer.registry.SkillsRegistryParser;
import dev.forgeide.importer.scaffold.PromptSection;
import dev.forgeide.importer.scaffold.ScaffoldCatalog;
import dev.forgeide.importer.scaffold.ScaffoldScanner;
import dev.forgeide.importer.scaffold.SkillSizeCheck;
import dev.forgeide.importer.scaffold.SubagentPromptSplitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * One import run: binds a bundled template against a scanned scaffold, tracks which tiles a user
 * had to rebind by hand, and assembles the final {@link ImportResult} once nothing is left
 * unmatched (SD §8: "Несматченные плитки подсвечиваются: пользователь указывает файл вручную").
 * A UI screen owns one instance per "pick a source directory + template" attempt; it is the only
 * mutable piece of the importer, everything it delegates to ({@code ImportBinder}, {@code
 * ScaffoldScanner}) is a pure function.
 */
public final class ImportSession {

    private final PipelineDefinition template;
    private final ScaffoldCatalog catalog;
    private final Map<String, TileBinding> bindings = new LinkedHashMap<>();

    public ImportSession(PipelineDefinition template, ScaffoldCatalog catalog) {
        this.template = Objects.requireNonNull(template, "template");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        for (TileBinding binding : ImportBinder.bind(template, catalog)) {
            bindings.put(binding.key(), binding);
        }
    }

    public PipelineDefinition template() {
        return template;
    }

    public List<TileBinding> bindings() {
        return List.copyOf(bindings.values());
    }

    public List<TileBinding.Unmatched> unmatched() {
        return bindings.values().stream()
                .filter(TileBinding.Unmatched.class::isInstance)
                .map(TileBinding.Unmatched.class::cast)
                .toList();
    }

    /** T33: tiles {@link ImportBinder} matched to more than one section — still block {@link
     * #result()} same as {@link #unmatched()}, resolved one candidate at a time via {@link
     * #resolveAmbiguous}. */
    public List<TileBinding.Ambiguous> ambiguous() {
        return bindings.values().stream()
                .filter(TileBinding.Ambiguous.class::isInstance)
                .map(TileBinding.Ambiguous.class::cast)
                .toList();
    }

    public boolean isComplete() {
        return unmatched().isEmpty() && ambiguous().isEmpty();
    }

    /** T34: ids of skill directories whose on-disk footprint (minus {@link
     * dev.forgeide.importer.scaffold.ScaffoldScanner#SKIP_DIRS} noise) exceeds {@link
     * SkillSizeCheck#WARN_THRESHOLD_BYTES} — the whole directory rides into the target project
     * now, so the UI surfaces this before import instead of silently dragging along a stray
     * binary/data file. */
    public List<String> oversizedSkills() {
        return catalog.skillDirs().entrySet().stream()
                .filter(e -> SkillSizeCheck.isSuspiciouslyLarge(e.getValue()))
                .map(Map.Entry::getKey)
                .toList();
    }

    /** SD §8's manual-binding fallback: the caller already ran a file dialog, this just records
     * the pick and reads its content — same shape auto-matching produces. Whole file, verbatim;
     * for a heading-bearing markdown file the caller should offer {@link #sectionsOf} and go
     * through {@link #bindManuallyToSection} instead so the tile gets one section's body, not
     * everything (T33). */
    public void bindManually(String key, Path chosenFile) {
        TileBinding existing = bindings.get(key);
        if (existing == null) {
            throw new IllegalArgumentException("unknown binding key: " + key);
        }
        if (!Files.isRegularFile(chosenFile)) {
            throw new IllegalArgumentException("not a file: " + chosenFile);
        }
        bindings.put(key, new TileBinding.Matched(key, existing.targetPath(), chosenFile, readString(chosenFile)));
    }

    /** T33's §-section manual bind: {@code section} came from {@link #sectionsOf} on a file the
     * caller already ran a file dialog for — only its body becomes the tile's content, the same
     * shape auto-matching a {@code subagent-prompts.md} heading produces. */
    public void bindManuallyToSection(String key, PromptSection section) {
        TileBinding existing = bindings.get(key);
        if (existing == null) {
            throw new IllegalArgumentException("unknown binding key: " + key);
        }
        bindings.put(key, new TileBinding.Matched(key, existing.targetPath(), section.sourceFile(), section.body()));
    }

    /** T33: resolves a {@link TileBinding.Ambiguous} tile by picking one of the candidates {@link
     * ImportBinder} already found — the user's choice, not another guess. */
    public void resolveAmbiguous(String key, PromptSection chosen) {
        TileBinding existing = bindings.get(key);
        if (!(existing instanceof TileBinding.Ambiguous ambiguous)) {
            throw new IllegalArgumentException("not an ambiguous binding: " + key);
        }
        if (!ambiguous.candidates().contains(chosen)) {
            throw new IllegalArgumentException("not a candidate for " + key + ": " + chosen.heading());
        }
        bindings.put(key, new TileBinding.Matched(key, existing.targetPath(), chosen.sourceFile(), chosen.body()));
    }

    /** T33: heading-delimited slices of {@code file}, for the §-section picker a manual bind onto
     * a markdown file (e.g. another {@code subagent-prompts.md}) should offer — empty when the
     * file isn't markdown or has no headings, meaning it should be bound whole via {@link
     * #bindManually} instead. */
    public static List<PromptSection> sectionsOf(Path file) {
        if (!file.getFileName().toString().endsWith(".md")) {
            return List.of();
        }
        return SubagentPromptSplitter.split(file, readString(file));
    }

    /** @throws IllegalStateException while {@link #unmatched()} or {@link #ambiguous()} is
     *                                 non-empty — same "can't finish with tiles still highlighted"
     *                                 doctrine the UI enforces visually. */
    public ImportResult result() {
        if (!isComplete()) {
            List<String> pending = new ArrayList<>(unmatched().stream().map(TileBinding::key).toList());
            pending.addAll(ambiguous().stream().map(TileBinding::key).toList());
            throw new IllegalStateException("cannot finish import while tiles remain unmatched: " + pending);
        }

        Map<Path, String> files = new LinkedHashMap<>();
        Map<String, String> stepToRegistryId = new LinkedHashMap<>();
        for (TileBinding binding : bindings.values()) {
            TileBinding.Matched matched = (TileBinding.Matched) binding;
            files.put(matched.targetPath(), matched.content());
            ImportBinder.skillIdFor(matched.sourcePath(), catalog)
                    .ifPresent(skillId -> stepToRegistryId.put(baseStepId(matched.key()), skillId));
        }

        // T32: settings.hooks.json lands at the harness root, not under hooks/ — that's where
        // preflight.py and HarnessLayout.SETTINGS_FILE / the hash-manifest expect to find it.
        catalog.hooksFile().ifPresent(hooks ->
                files.put(Path.of(".gigacode/settings.hooks.json"), readString(hooks)));
        // T34: the whole skill directory travels, not just SKILL.md — a skill whose SKILL.md
        // references references/... or ships extra check_*.py alongside its judge-bound ones
        // needs those files to actually exist in the target project for gigacode's
        // autodiscovery/preflight to resolve them (SD §8, ревью импортёра 2026-07-11 №3).
        catalog.skillDirs().forEach((id, dir) -> copySkillDir(id, dir, files));

        List<SkillsRegistryEntry> registry = catalog.registryFile()
                .map(file -> SkillsRegistryParser.parse(readString(file)))
                .orElse(List.of());
        // A copy travels into the target project (SD §9 "хуки/реестр деплоятся в .gigacode") so
        // a later IDE session can rebuild the same validity checker without re-scanning the
        // original scaffold — see ProjectValidityCheckers.
        catalog.registryFile().ifPresent(file ->
                files.put(Path.of(".gigacode/SKILLS-REGISTRY.md"), readString(file)));

        return new ImportResult(template, files, registry, stepToRegistryId);
    }

    /** {@code "judge-red.check"} → {@code "judge-red"} — the id the registry mapping and the
     * canvas both key by, stripping {@code ImportBinder}'s {@code .check}/{@code .llm} suffix. */
    private static String baseStepId(String bindingKey) {
        int dot = bindingKey.indexOf('.');
        return dot < 0 ? bindingKey : bindingKey.substring(0, dot);
    }

    /** Copies every regular file under {@code dir} (a skill directory {@code ScaffoldScanner}
     * found) into {@code files}, keyed under {@code .gigacode/skills/<id>/...} with {@code dir}'s
     * own relative layout preserved — same {@link ScaffoldScanner#SKIP_DIRS} noise filter the
     * scanner itself uses, so VCS/venv/build junk that happened to sit inside the skill checkout
     * never rides along (T34). */
    private static void copySkillDir(String id, Path dir, Map<Path, String> files) {
        Path base = Path.of(".gigacode/skills/" + id);
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> !ScaffoldScanner.isUnderSkippedDir(dir, p))
                    .sorted()
                    .forEach(p -> files.put(base.resolve(dir.relativize(p)), readString(p)));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot walk skill dir " + dir, e);
        }
    }

    private static String readString(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }
}
