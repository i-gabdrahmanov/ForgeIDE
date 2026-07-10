package dev.forgeide.importer.scaffold;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Read-only walk over a Forge-обвязка checkout (SD §8: "Сканер каталога обвязки: skills/*&#47;SKILL.md,
 * subagent-prompts.md, hooks/settings.hooks.json, скрипты check_*.py"). Real repos nest these at
 * varying depths (e.g. {@code skills/<name>/references/subagent-prompts.md}), so the scanner walks
 * the whole tree by filename rather than assuming a fixed layout — same doctrine T24's scope note
 * documents for why this stays filename/heading-convention matching, not prose parsing.
 */
public final class ScaffoldScanner {

    /** Directories skipped outright — VCS/venv/build noise a real developer checkout drags in. */
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".venv", "__pycache__", "node_modules", "build", ".idea", ".gradle", ".gigacode-cache");

    private ScaffoldScanner() {
    }

    public static ScaffoldCatalog scan(Path root) {
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("not a directory: " + root);
        }
        List<Path> files = walk(root);

        Map<String, Path> skillDirs = scanSkillDirs(root);

        List<PromptSection> promptSections = new ArrayList<>();
        for (Path candidate : filesNamed(files, "subagent-prompts.md")) {
            promptSections.addAll(SubagentPromptSplitter.split(candidate, readString(candidate)));
        }

        Optional<Path> hooksFile = filesNamed(files, "settings.hooks.json").stream().findFirst();
        Optional<Path> registryFile = filesNamed(files, "SKILLS-REGISTRY.md").stream().findFirst();
        List<Path> checkScripts = files.stream()
                .filter(p -> p.getFileName().toString().matches("check_.*\\.py"))
                .sorted()
                .toList();

        return new ScaffoldCatalog(root, skillDirs, promptSections, hooksFile, checkScripts, registryFile);
    }

    private static Map<String, Path> scanSkillDirs(Path root) {
        Map<String, Path> skillDirs = new LinkedHashMap<>();
        Path skillsDir = root.resolve("skills");
        if (!Files.isDirectory(skillsDir)) {
            return skillDirs;
        }
        try (Stream<Path> entries = Files.list(skillsDir)) {
            entries.filter(Files::isDirectory).sorted().forEach(dir -> {
                if (Files.isRegularFile(dir.resolve("SKILL.md"))) {
                    skillDirs.put(dir.getFileName().toString(), dir);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list " + skillsDir, e);
        }
        return skillDirs;
    }

    private static List<Path> filesNamed(List<Path> files, String name) {
        return files.stream().filter(p -> p.getFileName().toString().equals(name)).sorted().toList();
    }

    private static List<Path> walk(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> !isUnderSkippedDir(root, p))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot walk " + root, e);
        }
    }

    private static boolean isUnderSkippedDir(Path root, Path file) {
        Path relative = root.relativize(file.getParent() == null ? file : file.getParent());
        for (Path segment : relative) {
            if (SKIP_DIRS.contains(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    private static String readString(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }
}
