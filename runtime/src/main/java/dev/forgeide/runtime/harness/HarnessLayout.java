package dev.forgeide.runtime.harness;

import dev.forgeide.runtime.state.ProjectHash;

import java.nio.file.Path;

/**
 * On-disk layout for harness integrity (SD §4/§6.1, SDD FR-1.4/SR-7/SR-8): the project's harness
 * itself lives at {@code <project>/.gigacode/}; everything the IDE keeps about it — the
 * content-addressed cache and the deployed baseline — lives under a {@code forgeideHome} root
 * (production default {@code ~/.forgeide/}, see {@link #defaultForgeideHome}), outside the
 * project, exactly as SR-7 requires ("вне проекта"). {@code forgeideHome} is threaded through
 * explicitly (never read from {@code user.home} internally) so tests can point it at a {@code
 * @TempDir} instead of touching a developer's real home — same split as {@code
 * FileStateStore(Path stateRoot)}.
 */
final class HarnessLayout {

    static final String HARNESS_DIR = ".gigacode";
    /** The placeholder-bearing template a forge harness ships under {@code hooks/} (SD/T41): the
     * source of truth the resolver expands. Not what the runtime reads — that's {@link
     * #RESOLVED_FILE}. */
    static final String TEMPLATE_FILE = "settings.hooks.json";
    /** The resolved config the agent runtime (gigacode) actually reads, at the harness root —
     * generated at deploy time from {@link #TEMPLATE_FILE} by {@link #RESOLVER_SCRIPT} (or the
     * bundled fallback). Never hand-edited, never hashed (it is project-path-derived). */
    static final String RESOLVED_FILE = "settings.json";
    /** Harness-provided resolver under {@code hooks/} (forge's {@code resolve_hook_paths.py}):
     * expands {@code ${PROJECT_ROOT}}/{@code ${PYTHON}} from the template into {@link
     * #RESOLVED_FILE}. Absent for simple harnesses — then the bundled {@code resolve.py} runs. */
    static final String RESOLVER_SCRIPT = "resolve_hook_paths.py";
    /** Harness-provided preflight under {@code hooks/} (forge's {@code preflight.py}): its own,
     * richer validator. Absent for simple harnesses — then the bundled {@code preflight.py} runs. */
    static final String HARNESS_PREFLIGHT = "preflight.py";
    static final java.util.List<String> SCRIPT_SUBDIRS = java.util.List.of("hooks", "skills");

    private HarnessLayout() {
    }

    static Path harnessRoot(Path projectRoot) {
        return projectRoot.resolve(HARNESS_DIR);
    }

    static Path hooksDir(Path projectRoot) {
        return harnessRoot(projectRoot).resolve("hooks");
    }

    /** {@code <project>/.gigacode/hooks/settings.hooks.json} — the forge-native template location
     * (T41), where the resolver and the harness's own preflight look for it. */
    static Path templateFile(Path projectRoot) {
        return hooksDir(projectRoot).resolve(TEMPLATE_FILE);
    }

    /** {@code <project>/.gigacode/settings.json} — the resolved config gigacode reads at run time. */
    static Path resolvedFile(Path projectRoot) {
        return harnessRoot(projectRoot).resolve(RESOLVED_FILE);
    }

    static Path resolverScript(Path projectRoot) {
        return hooksDir(projectRoot).resolve(RESOLVER_SCRIPT);
    }

    static Path harnessPreflight(Path projectRoot) {
        return hooksDir(projectRoot).resolve(HARNESS_PREFLIGHT);
    }

    static Path defaultForgeideHome() {
        return Path.of(System.getProperty("user.home"), ".forgeide");
    }

    /** {@code <forgeideHome>/harness-cache/<hash>/} (SR-7): a copy of the harness tree, named
     * after its own hash-manifest so the same content always lands in the same cache entry. */
    static Path cacheDir(Path forgeideHome, String hash) {
        return forgeideHome.resolve("harness-cache").resolve(hash);
    }

    /** {@code <forgeideHome>/harness/<project-hash>/registry.json} — the deployed/accepted
     * baseline for a given project (SD §4's {@code <project-hash>} convention, same as {@code
     * FileStateStore#defaultRoot}). */
    static Path registryFile(Path forgeideHome, Path projectRoot) {
        return forgeideHome.resolve("harness").resolve(ProjectHash.of(projectRoot)).resolve("registry.json");
    }

    /** Where the bundled {@code deploy.sh}/{@code preflight.py} wrappers (FR-1.4) are
     * materialized before being invoked as real OS processes. */
    static Path binDir(Path forgeideHome) {
        return forgeideHome.resolve("bin");
    }
}
