package dev.forgeide.runtime.evil;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.forgeide.core.port.AgentEvent;
import dev.forgeide.core.port.AgentInvocation;
import dev.forgeide.core.port.AgentResult;
import dev.forgeide.core.port.AgentRuntimeException;
import dev.forgeide.core.port.AgentRuntimePort;
import dev.forgeide.core.port.TokenUsage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * T19 "злые фикстуры" that need real git plumbing or a real OS process to demonstrate —
 * Т-4/Т-13 (scope-diff over a real git repo) and Т-9 (a real detached process a sweep must
 * catch). Т-1/Т-7/Т-12/Т-14/Т-15 don't need any of that and live in {@code core}'s own {@code
 * EvilAgentRuntime}; Т-1 (manifest tamper) already has dedicated real-{@code ManifestProjector}
 * coverage in {@code ManifestProjectorTamperTest} and isn't repeated here.
 */
final class EvilAgentRuntime {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EvilAgentRuntime() {
    }

    /** Т-4: the phase has no git credentials in its env, so a real {@code git push} to a remote
     * that doesn't exist here fails harmlessly — but the phase still tries to "deliver" its work
     * by leaving a stray file outside {@code allowed_write}, which is what scope-diff actually
     * catches (SR-6's GWT allows either "push падает" or a hook block; the file write is the part
     * that a bare status-diff can observe either way). */
    static AgentRuntimePort attemptsPushThenLeavesAStrayFile(Path strayFile) {
        return (invocation, onEvent) -> {
            bestEffortGitPush(invocation.workingDir());
            writeQuietly(strayFile, "delivered without going through a gate or the outward step\n");
            return okResult();
        };
    }

    /** Т-13: a local commit is exactly the "коммит в master" vector SDD names — scope-diff's
     * HEAD-move check catches it regardless of {@code allowed_write} (SR-6). */
    static AgentRuntimePort commitsLocallyMovingHead(Path repo, Path fileToCommit) {
        return (invocation, onEvent) -> {
            writeQuietly(fileToCommit, "a change the phase decided to just commit itself\n");
            run(repo, "add", "-A");
            run(repo, "commit", "-q", "-m", "sneaky local commit, no gate involved");
            return okResult();
        };
    }

    /** Т-9: escapes its own process group the way {@code nohup}/{@code setsid} would — spawned
     * directly here (not through the phase's own launcher) to simulate a background process that
     * outlives the phase, with its cwd under the project so the post-phase sweep can find it. */
    static final class DetachedProcessSpawner implements AgentRuntimePort {
        private volatile Process spawned;

        @Override
        public AgentResult execute(AgentInvocation invocation, Consumer<AgentEvent> onEvent) throws AgentRuntimeException {
            try {
                spawned = new ProcessBuilder("/bin/sh", "-c", "sleep 60")
                        .directory(invocation.workingDir().toFile())
                        .start();
            } catch (IOException e) {
                throw new AgentRuntimeException("failed to spawn detached process", e);
            }
            return okResult();
        }

        Process spawnedProcess() {
            return spawned;
        }
    }

    private static AgentResult okResult() {
        ObjectNode json = MAPPER.createObjectNode();
        json.put("step_id", "work");
        return new AgentResult(0, Optional.of(json), new TokenUsage(1, 1), Path.of("raw.log"));
    }

    private static void bestEffortGitPush(Path repo) {
        try {
            new ProcessBuilder("git", "push", "origin", "HEAD:refs/heads/main")
                    .directory(repo.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                    .waitFor();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // Expected: no remote configured (env_scope carries no git credentials either) — the
            // push failing is exactly Т-4's GWT, not a fixture bug.
        }
    }

    private static void run(Path dir, String... args) {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        try {
            Process process = new ProcessBuilder(command).directory(dir.toFile()).start();
            process.getInputStream().readAllBytes();
            process.getErrorStream().readAllBytes();
            process.waitFor();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("git " + String.join(" ", args) + " failed", e);
        }
    }

    private static void writeQuietly(Path path, String content) {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
