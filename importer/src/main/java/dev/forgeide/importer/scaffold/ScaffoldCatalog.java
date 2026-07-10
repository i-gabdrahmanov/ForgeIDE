package dev.forgeide.importer.scaffold;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything {@link ScaffoldScanner} found under one Forge-обвязка root (SD §8, T24 scope): the
 * skill directories (each holding a {@code SKILL.md}), every heading-sliced section of every
 * {@code subagent-prompts.md} found, the deploy-ready hooks config, every {@code check_*.py}
 * judge script, and the tile registry file if present. This is read-only bookkeeping — matching
 * it against a template's steps is {@code ImportBinder}'s job, not the scanner's.
 */
public record ScaffoldCatalog(
        Path root,
        Map<String, Path> skillDirs,
        List<PromptSection> promptSections,
        Optional<Path> hooksFile,
        List<Path> checkScripts,
        Optional<Path> registryFile
) {

    public ScaffoldCatalog {
        Objects.requireNonNull(root, "root");
        skillDirs = Map.copyOf(skillDirs);
        promptSections = List.copyOf(promptSections);
        Objects.requireNonNull(hooksFile, "hooksFile");
        checkScripts = List.copyOf(checkScripts);
        Objects.requireNonNull(registryFile, "registryFile");
    }
}
