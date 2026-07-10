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
