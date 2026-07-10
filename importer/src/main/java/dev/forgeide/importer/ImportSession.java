package dev.forgeide.importer;

import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.importer.bind.ImportBinder;
import dev.forgeide.importer.bind.TileBinding;
import dev.forgeide.importer.registry.SkillsRegistryEntry;
import dev.forgeide.importer.registry.SkillsRegistryParser;
import dev.forgeide.importer.scaffold.ScaffoldCatalog;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

    public boolean isComplete() {
        return unmatched().isEmpty();
    }

    /** SD §8's manual-binding fallback: the caller already ran a file dialog, this just records
     * the pick and reads its content — same shape auto-matching produces. */
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

    /** @throws IllegalStateException while {@link #unmatched()} is non-empty — same "can't finish
     *                                 with tiles still highlighted" doctrine the UI enforces visually. */
    public ImportResult result() {
        if (!isComplete()) {
            throw new IllegalStateException("cannot finish import while tiles remain unmatched: "
                    + unmatched().stream().map(TileBinding::key).toList());
        }

        Map<Path, String> files = new LinkedHashMap<>();
        Map<String, String> stepToRegistryId = new LinkedHashMap<>();
        for (TileBinding binding : bindings.values()) {
            TileBinding.Matched matched = (TileBinding.Matched) binding;
            files.put(matched.targetPath(), matched.content());
            ImportBinder.skillIdFor(matched.sourcePath(), catalog)
                    .ifPresent(skillId -> stepToRegistryId.put(baseStepId(matched.key()), skillId));
        }

        catalog.hooksFile().ifPresent(hooks ->
                files.put(Path.of(".gigacode/hooks/settings.hooks.json"), readString(hooks)));
        catalog.skillDirs().forEach((id, dir) -> {
            Path skillMd = dir.resolve("SKILL.md");
            if (Files.isRegularFile(skillMd)) {
                files.put(Path.of(".gigacode/skills/" + id + "/SKILL.md"), readString(skillMd));
            }
        });

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

    private static String readString(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }
}
