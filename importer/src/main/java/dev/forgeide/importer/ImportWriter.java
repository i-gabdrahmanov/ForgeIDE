package dev.forgeide.importer;

import dev.forgeide.core.pipeline.yaml.PipelineYaml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Writes a completed {@link ImportResult} into a target project (SD §8: "Результат импорта —
 * валидный pipeline.yaml + каталог prompts/ в целевом проекте"). Pure I/O, no matching logic —
 * everything about what goes where was already decided when {@link ImportSession#result()} was
 * built.
 */
public final class ImportWriter {

    private static final String FORGEIDE_DIR = ".forgeide";
    private static final String PIPELINE_FILE = "pipeline.yaml";
    private static final String GITIGNORE_FILE = ".gitignore";
    /** SD §6.2's "raw-логи не трогаем, но ground/ai-logs/ добавляется в .gitignore" half of the
     * masking promise — untrusted agent stdout/stderr must never end up committed. */
    private static final String AI_LOGS_ENTRY = "ground/ai-logs/";

    private ImportWriter() {
    }

    public static void write(Path projectRoot, ImportResult result) {
        Path forgeideDir = projectRoot.resolve(FORGEIDE_DIR);
        try {
            Files.createDirectories(forgeideDir);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create " + forgeideDir, e);
        }
        new PipelineYaml().write(result.pipeline(), forgeideDir.resolve(PIPELINE_FILE));

        Path root = projectRoot.normalize();
        for (Map.Entry<Path, String> file : result.files().entrySet()) {
            Path target = projectRoot.resolve(file.getKey()).normalize();
            if (!target.startsWith(root)) {
                throw new IllegalArgumentException("import file path escapes project root: " + file.getKey());
            }
            writeString(target, file.getValue());
        }

        new ImportManifest(result.stepToRegistryId()).write(ImportManifest.pathUnder(forgeideDir));
        ensureAiLogsIgnored(projectRoot);
    }

    /** Idempotent: a re-import over an already-patched {@code .gitignore} (any of the common
     * spellings — with/without a leading/trailing slash) leaves it untouched rather than piling
     * up duplicate lines. */
    private static void ensureAiLogsIgnored(Path projectRoot) {
        Path gitignore = projectRoot.resolve(GITIGNORE_FILE);
        try {
            String existing = Files.isRegularFile(gitignore)
                    ? Files.readString(gitignore, StandardCharsets.UTF_8)
                    : "";
            boolean alreadyIgnored = existing.lines().map(String::strip)
                    .anyMatch(line -> switch (line) {
                        case "ground/ai-logs/", "ground/ai-logs", "/ground/ai-logs/", "/ground/ai-logs" -> true;
                        default -> false;
                    });
            if (alreadyIgnored) {
                return;
            }
            StringBuilder patched = new StringBuilder(existing);
            if (!existing.isEmpty() && !existing.endsWith("\n")) {
                patched.append('\n');
            }
            patched.append(AI_LOGS_ENTRY).append('\n');
            Files.writeString(gitignore, patched.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot update " + gitignore, e);
        }
    }

    private static void writeString(Path file, String content) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }
}
