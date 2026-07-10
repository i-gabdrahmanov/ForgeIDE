package dev.forgeide.runtime.harness;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * T20: a {@code ScriptStep}/{@code JudgeStep.deterministicCheck} has no dedicated {@code Path}
 * field for its script — the file is just one token inside {@code command} (e.g. {@code
 * [python3, .gigacode/skills/forgelite/scripts/check_tests_red.py]}). The tile inspector's
 * "Script" tab needs to know which token that is before it can open/save it — same normalization
 * spirit as {@link DefaultHarnessGuard}'s private token resolution, but without any harness-cache
 * involvement: this just answers "which file", trusted-path routing is decided separately by
 * whether that file resolves under {@link HarnessLayout#harnessRoot}.
 */
public final class JudgeScriptLocator {

    private static final Set<String> KNOWN_INTERPRETERS =
            Set.of("python3", "python", "python2", "bash", "sh", "zsh", "node");

    private JudgeScriptLocator() {
    }

    /** First {@code command} token that is not a known interpreter and resolves to an existing
     * regular file under {@code projectRoot}; empty if no such token exists (e.g. an inline
     * one-liner with no script file at all). */
    public static Optional<Path> locate(Path projectRoot, List<String> command) {
        for (String token : command) {
            if (KNOWN_INTERPRETERS.contains(token) || token.startsWith("-")) {
                continue;
            }
            Path resolved;
            try {
                resolved = projectRoot.resolve(token).normalize();
            } catch (RuntimeException notAPath) {
                continue;
            }
            if (!resolved.startsWith(projectRoot)) {
                continue;
            }
            if (Files.isRegularFile(resolved)) {
                return Optional.of(projectRoot.relativize(resolved));
            }
        }
        return Optional.empty();
    }
}
