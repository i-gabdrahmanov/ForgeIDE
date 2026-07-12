package dev.forgeide.runtime.git;

import dev.forgeide.core.port.ScopeDiffPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Exercises {@link GitScopeDiff} (SDD SR-6/Т-4/Т-13) against a real {@code git} binary and a
 * throwaway repo. */
class GitScopeDiffTest {

    private final GitScopeDiff scopeDiff = new GitScopeDiff();

    @Test
    void editingAFileInsideAllowedWriteIsNotAViolation(@TempDir Path repo) throws IOException, InterruptedException {
        assumeGitAvailable();
        init(repo);
        Files.createDirectories(repo.resolve("src"));
        Files.writeString(repo.resolve("src/a.txt"), "one\n");
        run(repo, "add", "src/a.txt");
        run(repo, "commit", "-q", "-m", "initial");

        ScopeDiffPort.Snapshot before = scopeDiff.snapshot(repo);
        Files.writeString(repo.resolve("src/a.txt"), "two\n");

        List<String> violations = scopeDiff.violations(repo, before, List.of("src/**"));

        assertThat(violations).isEmpty();
    }

    @Test
    void newFileOutsideAllowedWriteIsAViolationListedByPath(@TempDir Path repo) throws IOException, InterruptedException {
        assumeGitAvailable();
        init(repo);
        Files.createDirectories(repo.resolve("src"));
        Files.writeString(repo.resolve("src/a.txt"), "one\n");
        run(repo, "add", "src/a.txt");
        run(repo, "commit", "-q", "-m", "initial");

        ScopeDiffPort.Snapshot before = scopeDiff.snapshot(repo);
        Files.createDirectories(repo.resolve("secrets"));
        Files.writeString(repo.resolve("secrets/leak.txt"), "oops\n");

        List<String> violations = scopeDiff.violations(repo, before, List.of("src/**"));

        assertThat(violations).containsExactly("secrets/leak.txt");
    }

    @Test
    void aNewFileMatchingTheMaskIsNotAViolation(@TempDir Path repo) throws IOException, InterruptedException {
        assumeGitAvailable();
        init(repo);
        run(repo, "commit", "--allow-empty", "-q", "-m", "initial");

        ScopeDiffPort.Snapshot before = scopeDiff.snapshot(repo);
        Files.createDirectories(repo.resolve("src"));
        Files.writeString(repo.resolve("src/new.txt"), "brand new\n");

        List<String> violations = scopeDiff.violations(repo, before, List.of("src/**"));

        assertThat(violations).isEmpty();
    }

    @Test
    void aCommitDuringThePhaseIsFlaggedEvenWhenTheFileItselfMatchesTheMask(
            @TempDir Path repo) throws IOException, InterruptedException {
        assumeGitAvailable();
        init(repo);
        Files.createDirectories(repo.resolve("src"));
        Files.writeString(repo.resolve("src/a.txt"), "one\n");
        run(repo, "add", "src/a.txt");
        run(repo, "commit", "-q", "-m", "initial");

        ScopeDiffPort.Snapshot before = scopeDiff.snapshot(repo);
        Files.writeString(repo.resolve("src/a.txt"), "two\n");
        run(repo, "commit", "-aq", "-m", "sneaky local commit");

        List<String> violations = scopeDiff.violations(repo, before, List.of("src/**"));

        assertThat(violations).anyMatch(v -> v.startsWith(".git (HEAD moved"));
    }

    @Test
    void rollbackDeletesAnUntrackedViolationAndRestoresAModifiedTrackedOne(
            @TempDir Path repo) throws IOException, InterruptedException {
        assumeGitAvailable();
        init(repo);
        Files.createDirectories(repo.resolve("src"));
        Files.writeString(repo.resolve("src/a.txt"), "original\n");
        run(repo, "add", "src/a.txt");
        run(repo, "commit", "-q", "-m", "initial");

        ScopeDiffPort.Snapshot before = scopeDiff.snapshot(repo);
        Files.writeString(repo.resolve("src/a.txt"), "tampered\n");
        Files.createDirectories(repo.resolve("secrets"));
        Files.writeString(repo.resolve("secrets/leak.txt"), "oops\n");

        List<String> violations = scopeDiff.violations(repo, before, List.of());
        assertThat(violations).contains("src/a.txt", "secrets/leak.txt");

        List<String> restored = scopeDiff.rollback(repo, violations);

        assertThat(restored).containsExactlyInAnyOrder("src/a.txt", "secrets/leak.txt");
        assertThat(Files.readString(repo.resolve("src/a.txt"))).isEqualTo("original\n");
        assertThat(Files.exists(repo.resolve("secrets/leak.txt"))).isFalse();
    }

    @Test
    void nonRepoDirectoryYieldsAnEmptySnapshotRatherThanThrowing(@TempDir Path notARepo) {
        ScopeDiffPort.Snapshot snapshot = scopeDiff.snapshot(notARepo);

        assertThat(snapshot.statusByPath()).isEmpty();
        assertThat(snapshot.head()).isNull();
    }

    /** NFR-4 (SDD §6, task T31): the SDD names 100k files explicitly ("на репозитории до 100k
     * файлов") — the prior 5k here was an unrecorded scale-down (audit 2026-07 finding). File
     * creation itself is excluded from the timed window; only the git-plumbing calls the
     * overhead budget actually applies to are measured. */
    @Test
    void manyUntrackedFilesStayWellWithinTheNfr4Budget(@TempDir Path repo) throws IOException, InterruptedException {
        assumeGitAvailable();
        init(repo);
        run(repo, "commit", "--allow-empty", "-q", "-m", "initial");
        Files.createDirectories(repo.resolve("src"));
        for (int i = 0; i < 100_000; i++) {
            Files.writeString(repo.resolve("src/file-" + i + ".txt"), "x");
        }

        long start = System.nanoTime();
        ScopeDiffPort.Snapshot before = scopeDiff.snapshot(repo);
        List<String> violations = scopeDiff.violations(repo, before, List.of("src/**"));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(violations).isEmpty();
        assertThat(elapsedMs).isLessThan(2_000);
    }

    private static void init(Path repo) throws IOException, InterruptedException {
        run(repo, "init", "-q", ".");
        run(repo, "config", "user.email", "test@example.com");
        run(repo, "config", "user.name", "Test");
    }

    private static void assumeGitAvailable() {
        try {
            Process p = new ProcessBuilder("git", "--version").start();
            assumeTrue(p.waitFor() == 0, "git binary not available");
        } catch (IOException | InterruptedException e) {
            assumeTrue(false, "git binary not available");
        }
    }

    private static void run(Path dir, String... args) throws IOException, InterruptedException {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command).directory(dir.toFile()).start();
        process.getInputStream().readAllBytes();
        process.getErrorStream().readAllBytes();
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed with " + exit);
        }
    }
}
