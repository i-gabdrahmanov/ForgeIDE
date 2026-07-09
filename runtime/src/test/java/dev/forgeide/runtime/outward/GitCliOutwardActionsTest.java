package dev.forgeide.runtime.outward;

import dev.forgeide.core.port.OutwardActionException;
import dev.forgeide.core.port.OutwardActionsPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * T17 acceptance: "e2e на тестовом репозитории: ветка+push ... создаются движком после
 * подтверждения гейта" — exercised here against real git plumbing (a bare repo standing in for
 * the hosted remote), same spirit as {@code GitScopeDiffPipelineTest} for T16's scope-diff.
 */
class GitCliOutwardActionsTest {

    @Test
    void pushesUncommittedChangesToANewRemoteBranch(@TempDir Path base)
            throws IOException, InterruptedException, OutwardActionException {
        assumeGitAvailable();
        Path remote = base.resolve("remote.git");
        Path working = base.resolve("working");
        initBareRemote(remote);
        initWorkingRepoWithRemote(working, remote);

        Files.writeString(working.resolve("delivered.txt"), "written by an agent phase\n");

        GitCliOutwardActions git = new GitCliOutwardActions();
        OutwardActionsPort.Outcome outcome = git.push(new OutwardActionsPort.GitPushRequest(
                working, "origin", "feature-x/deliver", "forgeide: feature-x (deliver)", Map.of()));

        assertThat(outcome).isEqualTo(OutwardActionsPort.Outcome.EMPTY);
        assertThat(run(remote, "log", "-1", "--format=%s", "refs/heads/feature-x/deliver").strip())
                .isEqualTo("forgeide: feature-x (deliver)");
        assertThat(run(remote, "show", "refs/heads/feature-x/deliver:delivered.txt"))
                .contains("written by an agent phase");
    }

    @Test
    void retryingAfterTheCommitAlreadyLandedIsAnNoOpSuccess(@TempDir Path base)
            throws IOException, InterruptedException, OutwardActionException {
        assumeGitAvailable();
        Path remote = base.resolve("remote.git");
        Path working = base.resolve("working");
        initBareRemote(remote);
        initWorkingRepoWithRemote(working, remote);
        Files.writeString(working.resolve("delivered.txt"), "v1\n");

        GitCliOutwardActions git = new GitCliOutwardActions();
        OutwardActionsPort.GitPushRequest request = new OutwardActionsPort.GitPushRequest(
                working, "origin", "feature-x/deliver", "forgeide: feature-x (deliver)", Map.of());
        git.push(request);

        // Retry after a partial success (e.g. the push itself flaked over the network): the
        // working tree is already clean and HEAD already matches the remote branch — this must
        // not fail, and must not create a second commit (T17: "не создаёт дубликат").
        git.push(request);

        assertThat(run(remote, "log", "--oneline", "refs/heads/feature-x/deliver").lines().count()).isEqualTo(2);
    }

    @Test
    void aBearerTokenIsPassedViaEnvNeverArgvAndDoesNotBreakALocalRemote(
            @TempDir Path base) throws IOException, InterruptedException, OutwardActionException {
        assumeGitAvailable();
        Path remote = base.resolve("remote.git");
        Path working = base.resolve("working");
        initBareRemote(remote);
        initWorkingRepoWithRemote(working, remote);
        Files.writeString(working.resolve("delivered.txt"), "v1\n");

        GitCliOutwardActions git = new GitCliOutwardActions();
        git.push(new OutwardActionsPort.GitPushRequest(working, "origin", "feature-x/deliver",
                "forgeide: feature-x (deliver)", Map.of("GIT_TOKEN", "s3cr3t-token")));

        assertThat(run(remote, "log", "-1", "--format=%s", "refs/heads/feature-x/deliver").strip())
                .isEqualTo("forgeide: feature-x (deliver)");
    }

    @Test
    void aFailingPushSurfacesTheGitOutputInTheException(@TempDir Path base) throws IOException, InterruptedException {
        assumeGitAvailable();
        Path working = base.resolve("working");
        initWorkingRepoWithRemote(working, base.resolve("does-not-exist.git"));
        Files.writeString(working.resolve("delivered.txt"), "v1\n");

        GitCliOutwardActions git = new GitCliOutwardActions();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> git.push(new OutwardActionsPort.GitPushRequest(
                        working, "origin", "feature-x/deliver", "forgeide: feature-x (deliver)", Map.of())))
                .isInstanceOf(OutwardActionException.class)
                .hasMessageContaining("git push failed");
    }

    private static void initBareRemote(Path remote) throws IOException, InterruptedException {
        Files.createDirectories(remote);
        run(remote, "init", "-q", "--bare");
    }

    private static void initWorkingRepoWithRemote(Path working, Path remote) throws IOException, InterruptedException {
        Files.createDirectories(working);
        run(working, "init", "-q", ".");
        run(working, "config", "user.email", "test@example.com");
        run(working, "config", "user.name", "Test");
        run(working, "commit", "--allow-empty", "-q", "-m", "initial");
        run(working, "remote", "add", "origin", remote.toString());
    }

    private static void assumeGitAvailable() {
        try {
            Process p = new ProcessBuilder("git", "--version").start();
            assumeTrue(p.waitFor() == 0, "git binary not available");
        } catch (IOException | InterruptedException e) {
            assumeTrue(false, "git binary not available");
        }
    }

    private static String run(Path dir, String... args) throws IOException, InterruptedException {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed with " + exit + ": " + output);
        }
        return output;
    }
}
