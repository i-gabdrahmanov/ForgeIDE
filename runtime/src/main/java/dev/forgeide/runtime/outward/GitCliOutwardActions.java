package dev.forgeide.runtime.outward;

import dev.forgeide.core.port.OutwardActionException;
import dev.forgeide.core.port.OutwardActionsPort;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Real git-plumbing half of {@code OutwardActionsPort} (T17/SR-4): commits any uncommitted
 * working-tree changes left by the run's agent phases (a no-op if already clean — the retry-safe
 * path after a partial prior attempt) and pushes {@code HEAD} to a remote branch. The auth token
 * never touches argv (visible in {@code ps aux}) or a child process's inherited env — it rides in
 * via {@code GIT_CONFIG_*} env vars scoped to exactly this one invocation (git >= 2.31), the same
 * "credentials never leak further than they have to" spirit as {@code ProcessRunner}'s
 * stdin-for-prompts choice (SD §6.1).
 */
public final class GitCliOutwardActions {

    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(30);

    public OutwardActionsPort.Outcome push(OutwardActionsPort.GitPushRequest request) throws OutwardActionException {
        Path root = request.projectRoot();
        commitIfDirty(root, request.commitMessage());

        Map<String, String> env = new LinkedHashMap<>();
        String token = request.env().get("GIT_TOKEN");
        if (token != null && !token.isBlank()) {
            env.put("GIT_CONFIG_COUNT", "1");
            env.put("GIT_CONFIG_KEY_0", "http.extraHeader");
            env.put("GIT_CONFIG_VALUE_0", "Authorization: Bearer " + token);
        }
        GitResult result = run(root, env, "push", request.remote(), "HEAD:refs/heads/" + request.branch());
        if (result.exitCode() != 0) {
            throw new OutwardActionException("git push failed (exit " + result.exitCode() + "): " + result.output());
        }
        return OutwardActionsPort.Outcome.EMPTY;
    }

    /** Only an {@code outward} step may move {@code HEAD} (Т-13) — this is that one place. A
     * clean tree (nothing left to commit, e.g. a retry after the push itself failed over the
     * network) is not an error. */
    private void commitIfDirty(Path root, String message) throws OutwardActionException {
        GitResult status = run(root, Map.of(), "status", "--porcelain");
        if (status.exitCode() != 0) {
            throw new OutwardActionException("git status failed: " + status.output());
        }
        if (status.output().isBlank()) {
            return;
        }
        GitResult add = run(root, Map.of(), "add", "-A");
        if (add.exitCode() != 0) {
            throw new OutwardActionException("git add failed: " + add.output());
        }
        GitResult commit = run(root, Map.of(), "commit", "-q", "-m", message);
        if (commit.exitCode() != 0) {
            throw new OutwardActionException("git commit failed: " + commit.output());
        }
    }

    private static GitResult run(Path root, Map<String, String> extraEnv, String... args) throws OutwardActionException {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        String label = "git " + String.join(" ", args);

        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true);
            builder.environment().putAll(extraEnv);
            process = builder.start();
        } catch (IOException e) {
            throw new OutwardActionException("cannot start " + label, e);
        }

        try {
            // A single merged pipe drained to EOF before waitFor: same "drain, don't deadlock"
            // requirement as ProcessRunner, simplified because these are short git plumbing calls
            // with no interactive stdin and bounded output, unlike an agent phase's stream.
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(GIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new OutwardActionException(label + " timed out after " + GIT_TIMEOUT);
            }
            return new GitResult(process.exitValue(), output);
        } catch (IOException e) {
            throw new OutwardActionException(label + " I/O failure", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OutwardActionException("interrupted running " + label, e);
        } finally {
            process.destroyForcibly();
        }
    }

    private record GitResult(int exitCode, String output) {
    }
}
