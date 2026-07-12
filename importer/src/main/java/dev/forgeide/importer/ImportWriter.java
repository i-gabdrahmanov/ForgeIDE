package dev.forgeide.importer;

import dev.forgeide.core.pipeline.yaml.PipelineYaml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes a completed {@link ImportResult} into a target project (SD §8: "Результат импорта —
 * валидный pipeline.yaml + каталог prompts/ в целевом проекте"). Pure I/O, no matching logic —
 * everything about what goes where was already decided when {@link ImportSession#result()} was
 * built.
 *
 * <p>T35: a re-import must not clobber local edits silently. Every target file — {@code
 * pipeline.yaml}, the import manifest, and everything in {@link ImportResult#files()} (prompts,
 * judge scripts, the whole skill directory, the hooks/registry copies) — is compared against what
 * is already on disk before anything is written: identical content is left alone, a file that
 * does not exist yet is created outright, and a file whose content differs (SD §8, ревью
 * импортёра 2026-07-11 №4) is only overwritten when its relative path is explicitly confirmed via
 * {@link #write(Path, ImportResult, Set)} — {@link #plan} is how a caller finds out which paths
 * need that confirmation before calling {@code write}. A confirmed overwrite keeps its previous
 * content recoverable under {@code .forgeide/import-backup/<timestamp>/}.</p>
 */
public final class ImportWriter {

    private static final String FORGEIDE_DIR = ".forgeide";
    private static final String PIPELINE_FILE = "pipeline.yaml";
    private static final String GITIGNORE_FILE = ".gitignore";
    private static final String BACKUP_DIR = FORGEIDE_DIR + "/import-backup";
    private static final DateTimeFormatter BACKUP_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS").withZone(ZoneOffset.UTC);
    /** SD §6.2's "raw-логи не трогаем, но ground/ai-logs/ добавляется в .gitignore" half of the
     * masking promise — untrusted agent stdout/stderr must never end up committed. */
    private static final String AI_LOGS_ENTRY = "ground/ai-logs/";

    private ImportWriter() {
    }

    /** Where a target file stands relative to what {@link ImportResult} would put there. */
    public enum FileStatus {
        /** Nothing on disk at that path yet — written unconditionally. */
        NEW,
        /** Same content already on disk — left untouched, not even rewritten. */
        IDENTICAL,
        /** Different content already on disk — a conflict; only overwritten when confirmed. */
        MODIFIED
    }

    public record FileDiff(Path relativePath, FileStatus status) {
    }

    /** Every file this {@code result} would place under {@code projectRoot}, categorized against
     * what is already there — no writes. A caller (the import screen) uses this to show the user
     * which paths are {@link FileStatus#MODIFIED} conflicts before asking which of them, if any,
     * to overwrite. */
    public static List<FileDiff> plan(Path projectRoot, ImportResult result) {
        List<FileDiff> diffs = new ArrayList<>();
        for (Map.Entry<Path, String> entry : targetFiles(result).entrySet()) {
            Path target = resolveInsideRoot(projectRoot, entry.getKey());
            diffs.add(new FileDiff(entry.getKey(), statusOf(target, entry.getValue())));
        }
        return diffs;
    }

    /** Writes {@code result} into {@code projectRoot}, skipping every conflicting file that is
     * not named in {@code confirmedOverwrites} — the "skip conflicting" path is simply calling
     * this with an empty (or partial) set. A file overwritten this way is backed up first. */
    public static void write(Path projectRoot, ImportResult result, Set<Path> confirmedOverwrites) {
        String[] backupTimestamp = new String[1];
        for (Map.Entry<Path, String> entry : targetFiles(result).entrySet()) {
            Path relative = entry.getKey();
            Path target = resolveInsideRoot(projectRoot, relative);
            String newContent = entry.getValue();
            if (Files.isRegularFile(target)) {
                String existing = readString(target);
                if (existing.equals(newContent)) {
                    continue;
                }
                if (!confirmedOverwrites.contains(relative)) {
                    continue;
                }
                if (backupTimestamp[0] == null) {
                    backupTimestamp[0] = BACKUP_TIMESTAMP.format(Instant.now());
                }
                writeString(projectRoot.resolve(BACKUP_DIR).resolve(backupTimestamp[0]).resolve(relative), existing);
            }
            writeString(target, newContent);
        }
        ensureAiLogsIgnored(projectRoot);
    }

    /** Convenience for callers with nothing to confirm yet (a fresh project, or any caller that
     * has not asked the user about conflicts) — every {@link FileStatus#MODIFIED} path is left
     * untouched, same as passing an empty confirmation set. */
    public static void write(Path projectRoot, ImportResult result) {
        write(projectRoot, result, Set.of());
    }

    private static FileStatus statusOf(Path target, String newContent) {
        if (!Files.isRegularFile(target)) {
            return FileStatus.NEW;
        }
        return readString(target).equals(newContent) ? FileStatus.IDENTICAL : FileStatus.MODIFIED;
    }

    /** Every file an import writes, relative-path keyed, including the two that used to be
     * written straight to disk without going through the conflict check ({@code pipeline.yaml},
     * the import manifest) — folded in here so they get the same identical/modified treatment as
     * everything in {@link ImportResult#files()}. */
    private static Map<Path, String> targetFiles(ImportResult result) {
        Map<Path, String> files = new LinkedHashMap<>();
        files.put(Path.of(FORGEIDE_DIR, PIPELINE_FILE), new PipelineYaml().serialize(result.pipeline()));
        files.put(ImportManifest.pathUnder(Path.of(FORGEIDE_DIR)),
                new ImportManifest(result.stepToRegistryId()).toJson());
        files.putAll(result.files());
        return files;
    }

    private static Path resolveInsideRoot(Path projectRoot, Path relative) {
        Path root = projectRoot.normalize();
        Path target = projectRoot.resolve(relative).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("import file path escapes project root: " + relative);
        }
        return target;
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

    private static String readString(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
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
