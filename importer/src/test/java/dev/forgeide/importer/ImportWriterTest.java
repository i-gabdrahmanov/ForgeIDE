package dev.forgeide.importer;

import dev.forgeide.core.pipeline.yaml.PipelineTemplates;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T35 acceptance: {@link ImportWriter} must not clobber a target project's local edits on
 * re-import — every target file is compared against what is on disk first, and a file whose
 * content differs from the fresh import (a conflict) is only overwritten when its relative path
 * is explicitly confirmed, with the previous version kept recoverable under {@code
 * .forgeide/import-backup/<timestamp>/}.
 */
class ImportWriterTest {

    private static final Path PROMPT_A = Path.of("prompts/a.md");
    private static final Path PROMPT_B = Path.of("prompts/b.md");

    @Test
    void reimportWithNoChangesTouchesNoFile(@TempDir Path projectRoot) throws IOException {
        ImportResult result = resultOf(Map.of(PROMPT_A, "A content", PROMPT_B, "B content"));
        ImportWriter.write(projectRoot, result);

        Path fileA = projectRoot.resolve(PROMPT_A);
        FileTime before = Files.getLastModifiedTime(fileA);
        Files.setLastModifiedTime(fileA, FileTime.from(Instant.now().minus(1, ChronoUnit.HOURS)));
        FileTime backdated = Files.getLastModifiedTime(fileA);

        ImportWriter.write(projectRoot, result);

        assertThat(Files.getLastModifiedTime(fileA)).as("identical content must not be rewritten")
                .isEqualTo(backdated).isNotEqualTo(before);
        assertThat(ImportWriter.plan(projectRoot, result))
                .allSatisfy(diff -> assertThat(diff.status()).isEqualTo(ImportWriter.FileStatus.IDENTICAL));
    }

    @Test
    void planReportsNewIdenticalAndModified(@TempDir Path projectRoot) throws IOException {
        ImportResult result = resultOf(Map.of(PROMPT_A, "A content", PROMPT_B, "B content"));

        assertThat(ImportWriter.plan(projectRoot, result))
                .filteredOn(d -> d.relativePath().equals(PROMPT_A) || d.relativePath().equals(PROMPT_B))
                .allSatisfy(diff -> assertThat(diff.status()).isEqualTo(ImportWriter.FileStatus.NEW));

        ImportWriter.write(projectRoot, result);
        Files.writeString(projectRoot.resolve(PROMPT_A), "locally edited via T20 inspector");

        List<ImportWriter.FileDiff> plan = ImportWriter.plan(projectRoot, result);
        assertThat(statusOf(plan, PROMPT_A)).isEqualTo(ImportWriter.FileStatus.MODIFIED);
        assertThat(statusOf(plan, PROMPT_B)).isEqualTo(ImportWriter.FileStatus.IDENTICAL);
    }

    @Test
    void reimportWithALocallyEditedPromptShowsAConflictAndLeavesItUntouchedWithoutConfirmation(
            @TempDir Path projectRoot) throws IOException {
        ImportResult result = resultOf(Map.of(PROMPT_A, "A content"));
        ImportWriter.write(projectRoot, result);

        Files.writeString(projectRoot.resolve(PROMPT_A), "locally edited via T20 inspector");

        assertThat(statusOf(ImportWriter.plan(projectRoot, result), PROMPT_A))
                .isEqualTo(ImportWriter.FileStatus.MODIFIED);

        ImportWriter.write(projectRoot, result); // no confirmation — default "skip conflicting"

        assertThat(Files.readString(projectRoot.resolve(PROMPT_A))).isEqualTo("locally edited via T20 inspector");
    }

    @Test
    void confirmedOverwriteReplacesTheFileAndKeepsThePreviousVersionInBackup(
            @TempDir Path projectRoot) throws IOException {
        ImportResult result = resultOf(Map.of(PROMPT_A, "A content"));
        ImportWriter.write(projectRoot, result);

        Files.writeString(projectRoot.resolve(PROMPT_A), "locally edited via T20 inspector");

        ImportWriter.write(projectRoot, result, Set.of(PROMPT_A));

        assertThat(Files.readString(projectRoot.resolve(PROMPT_A))).isEqualTo("A content");

        Path backupRoot = projectRoot.resolve(".forgeide/import-backup");
        assertThat(backupRoot).isDirectory();
        try (Stream<Path> timestamps = Files.list(backupRoot)) {
            List<Path> runs = timestamps.toList();
            assertThat(runs).hasSize(1);
            Path backedUpFile = runs.get(0).resolve(PROMPT_A);
            assertThat(backedUpFile).isRegularFile();
            assertThat(Files.readString(backedUpFile)).isEqualTo("locally edited via T20 inspector");
        }
    }

    @Test
    void unconfirmedConflictDoesNotBlockWritingOtherNewFiles(@TempDir Path projectRoot) throws IOException {
        ImportResult first = resultOf(Map.of(PROMPT_A, "A content"));
        ImportWriter.write(projectRoot, first);
        Files.writeString(projectRoot.resolve(PROMPT_A), "locally edited via T20 inspector");

        ImportResult second = resultOf(Map.of(PROMPT_A, "A content", PROMPT_B, "B content"));
        ImportWriter.write(projectRoot, second);

        assertThat(Files.readString(projectRoot.resolve(PROMPT_A))).isEqualTo("locally edited via T20 inspector");
        assertThat(Files.readString(projectRoot.resolve(PROMPT_B))).isEqualTo("B content");
    }

    private static ImportWriter.FileStatus statusOf(List<ImportWriter.FileDiff> plan, Path relativePath) {
        return plan.stream().filter(d -> d.relativePath().equals(relativePath)).findFirst()
                .orElseThrow(() -> new AssertionError("no plan entry for " + relativePath)).status();
    }

    private static ImportResult resultOf(Map<Path, String> files) {
        return new ImportResult(PipelineTemplates.forgelite(), files, List.of(), Map.of());
    }
}
