package dev.forgeide.runtime.git;

import dev.forgeide.core.port.ScopeDiffPort;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Real git-plumbing {@link ScopeDiffPort} (SDD SR-6/Т-4/Т-13): {@code git status --porcelain}
 * (expanded with {@code --untracked-files=all} so a whole new directory shows up as its
 * individual files, not one collapsed entry) plus {@code git rev-parse HEAD} — both cheap,
 * index-backed reads that stay well within NFR-4's ≤2s budget even on a large repository, since
 * neither one walks the working tree from Java.
 *
 * <p>Comparing raw status codes before/after is a deliberate simplification: a path that was
 * already dirty before the phase and gets edited further during it keeps the same status code
 * (e.g. {@code "M "} stays {@code "M "}) and is not re-flagged — the same "detect, don't do a
 * full content walk" trade-off SR-6/NFR-4 call for elsewhere in this spec.
 */
public final class GitScopeDiff implements ScopeDiffPort {

    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(10);

    @Override
    public Snapshot snapshot(Path projectRoot) {
        return new Snapshot(statusByPath(projectRoot), head(projectRoot).orElse(null));
    }

    @Override
    public List<String> violations(Path projectRoot, Snapshot before, List<String> allowedWrite) {
        Snapshot after = snapshot(projectRoot);
        List<String> violations = new ArrayList<>();

        // Т-13: only an `outward` step, after its gate, may move HEAD (commit/reset/rewrite) —
        // an agent phase doing so is a scope violation regardless of allowed_write, and a plain
        // status diff would miss it entirely (a local commit makes `git status` clean again).
        if (before.head() != null && after.head() != null && !before.head().equals(after.head())) {
            violations.add(".git (HEAD moved " + before.head() + " -> " + after.head() + ")");
        }

        List<PathMatcher> matchers = allowedWrite.stream()
                .map(glob -> FileSystems.getDefault().getPathMatcher("glob:" + glob))
                .toList();

        for (Map.Entry<String, String> entry : after.statusByPath().entrySet()) {
            String path = entry.getKey();
            if (Objects.equals(before.statusByPath().get(path), entry.getValue())) {
                continue; // already in this exact dirty state before the phase started
            }
            if (!matchesAny(matchers, path)) {
                violations.add(path);
            }
        }
        return violations;
    }

    @Override
    public List<String> rollback(Path projectRoot, List<String> violations) {
        List<String> paths = violations.stream().filter(v -> !v.startsWith(".git (")).toList();
        if (paths.isEmpty()) {
            return List.of();
        }
        Map<String, String> currentStatus = statusByPath(projectRoot);
        List<String> restored = new ArrayList<>();
        for (String path : paths) {
            String status = currentStatus.get(path);
            boolean untracked = status != null && status.startsWith("?");
            if (untracked) {
                try {
                    if (Files.deleteIfExists(projectRoot.resolve(path))) {
                        restored.add(path);
                    }
                } catch (IOException e) {
                    // best-effort — left for the human to remove by hand
                }
            } else {
                boolean ok = run(projectRoot, List.of("git", "restore", "--source=HEAD", "--worktree", "--", path))
                        .map(r -> r.exitCode() == 0).orElse(false);
                if (ok) {
                    restored.add(path);
                }
            }
        }
        return restored;
    }

    private static boolean matchesAny(List<PathMatcher> matchers, String path) {
        Path asPath = Path.of(path);
        for (PathMatcher matcher : matchers) {
            if (matcher.matches(asPath)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, String> statusByPath(Path projectRoot) {
        Optional<GitResult> result = run(projectRoot, List.of("git", "status", "--porcelain", "--untracked-files=all"));
        Map<String, String> byPath = new LinkedHashMap<>();
        if (result.isEmpty() || result.get().exitCode() != 0) {
            return byPath;
        }
        for (String line : result.get().stdout().split("\n", -1)) {
            if (line.length() < 4) {
                continue;
            }
            String code = line.substring(0, 2);
            String rest = line.substring(3);
            int arrow = rest.indexOf(" -> ");
            String path = arrow >= 0 ? rest.substring(arrow + 4) : rest;
            byPath.put(unquote(path), code);
        }
        return byPath;
    }

    private static String unquote(String path) {
        if (path.length() >= 2 && path.startsWith("\"") && path.endsWith("\"")) {
            return path.substring(1, path.length() - 1);
        }
        return path;
    }

    private static Optional<String> head(Path projectRoot) {
        return run(projectRoot, List.of("git", "rev-parse", "HEAD"))
                .filter(r -> r.exitCode() == 0)
                .map(r -> r.stdout().strip())
                .filter(s -> !s.isBlank());
    }

    /** Best-effort git invocation: a non-repo working dir, a missing {@code git} binary, or a
     * hung process all just yield {@link Optional#empty()} (same spirit as {@code GitDiffReader}/
     * {@code GitHead}) — scope-diff fails open rather than blocking a phase on tooling trouble. */
    private static Optional<GitResult> run(Path projectRoot, List<String> command) {
        Process process;
        try {
            process = new ProcessBuilder(command)
                    .directory(projectRoot.toFile())
                    .redirectErrorStream(false)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException e) {
            return Optional.empty();
        }

        Thread watchdog = Thread.ofVirtual().start(() -> {
            try {
                if (!process.waitFor(GIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        try {
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor();
            return Optional.of(new GitResult(process.exitValue(), stdout));
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            watchdog.interrupt();
            process.destroyForcibly();
        }
    }

    private record GitResult(int exitCode, String stdout) {
    }
}
