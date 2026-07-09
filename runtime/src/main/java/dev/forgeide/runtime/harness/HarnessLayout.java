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
    static final String SETTINGS_FILE = "settings.hooks.json";
    static final java.util.List<String> SCRIPT_SUBDIRS = java.util.List.of("hooks", "skills");

    private HarnessLayout() {
    }

    static Path harnessRoot(Path projectRoot) {
        return projectRoot.resolve(HARNESS_DIR);
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
