package dev.forgeide.runtime.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Exercises {@link GitDiffReader} against a real {@code git} binary and a throwaway repo. */
class GitDiffReaderTest {

    @Test
    void reportsNoChangesOnAPristineCheckout(@TempDir Path repo) throws IOException, InterruptedException {
        assumeGitAvailable();
        run(repo, "init", "-q", ".");
        run(repo, "config", "user.email", "test@example.com");
        run(repo, "config", "user.name", "Test");
        Files.writeString(repo.resolve("a.txt"), "hello\n");
        run(repo, "add", "a.txt");
        run(repo, "commit", "-q", "-m", "initial");

        String diff = GitDiffReader.read(repo, Duration.ofSeconds(5));

        assertThat(diff).isEqualTo("(no changes)");
    }

    @Test
    void showsRealUnstagedEditsFromDisk(@TempDir Path repo) throws IOException, InterruptedException {
        assumeGitAvailable();
        run(repo, "init", "-q", ".");
        run(repo, "config", "user.email", "test@example.com");
        run(repo, "config", "user.name", "Test");
        Files.writeString(repo.resolve("a.txt"), "hello\n");
        run(repo, "add", "a.txt");
        run(repo, "commit", "-q", "-m", "initial");
        Files.writeString(repo.resolve("a.txt"), "hello, world\n");

        String diff = GitDiffReader.read(repo, Duration.ofSeconds(5));

        assertThat(diff).contains("-hello").contains("+hello, world");
    }

    @Test
    void nonRepoDirectoryYieldsAPlaceholderRatherThanThrowing(@TempDir Path notARepo) {
        String diff = GitDiffReader.read(notARepo, Duration.ofSeconds(5));

        assertThat(diff).startsWith("(git diff unavailable");
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
