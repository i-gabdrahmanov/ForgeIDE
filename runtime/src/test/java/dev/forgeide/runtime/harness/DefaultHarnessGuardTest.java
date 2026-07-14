package dev.forgeide.runtime.harness;

import dev.forgeide.core.port.HarnessGuardPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Exercises {@link DefaultHarnessGuard} (SDD FR-1.4/FR-8.3/SR-7/SR-8, T41 forge layout) against a
 * real filesystem and the bundled {@code deploy.sh}/{@code resolve.py}/{@code preflight.py}
 * wrappers. The forge layout: the {@code settings.hooks.json} template lives under {@code hooks/},
 * deploy generates the resolved {@code settings.json} the runtime reads, and preflight validates
 * that resolved file.
 */
class DefaultHarnessGuardTest {

    @Test
    void preflightIsNotPassedBeforeAnyDeploy(@TempDir Path project, @TempDir Path forgeideHome) {
        DefaultHarnessGuard guard = new DefaultHarnessGuard(forgeideHome);

        HarnessGuardPort.PreflightStatus status = guard.preflightStatus(project);

        assertThat(status.passed()).isFalse();
        assertThat(status.detail()).contains("not deployed");
        assertThat(status.deployedAt()).isEmpty();
    }

    @Test
    void deployingAValidHarnessPasses(@TempDir Path project, @TempDir Path forgeideHome) throws IOException {
        assumePython3Available();
        writeValidHarness(project);
        DefaultHarnessGuard guard = new DefaultHarnessGuard(forgeideHome);

        Instant before = Instant.now();
        HarnessGuardPort.DeployResult result = guard.deploy(project);

        assertThat(result.preflightPassed()).as(result.preflightOutput()).isTrue();
        // T41: deploy generates the resolved settings.json the agent runtime reads.
        assertThat(project.resolve(".gigacode/settings.json")).isRegularFile();
        HarnessGuardPort.PreflightStatus status = guard.preflightStatus(project);
        assertThat(status.passed()).isTrue();
        // T37: the project card shows this next to the deploy button (SDD FR-1.4).
        assertThat(status.deployedAt()).isPresent().hasValueSatisfying(at -> assertThat(at).isAfterOrEqualTo(before));
        assertThat(forgeideHome.resolve("harness-cache").resolve(result.hash())
                .resolve("hooks").resolve("tdd-guard.py")).exists();
    }

    /** T38: a hook reference is routinely a whole command line, not a bare path — preflight
     * must resolve the script token inside it instead of treating the string as one path. */
    @Test
    void preflightResolvesHookScriptTokensInsideCommandStrings(@TempDir Path project, @TempDir Path forgeideHome)
            throws IOException {
        assumePython3Available();
        Path harness = project.resolve(".gigacode");
        Files.createDirectories(harness.resolve("hooks"));
        Files.writeString(harness.resolve("hooks/tdd-guard.py"), "print('guarding')\n");
        Files.writeString(harness.resolve("hooks/settings.hooks.json"), """
                {"hooks": {"PreToolUse": [{"matcher": "Write|Edit", "hooks": [
                  {"type": "command", "command": "python3 hooks/tdd-guard.py"}]}]}}
                """);
        DefaultHarnessGuard guard = new DefaultHarnessGuard(forgeideHome);

        HarnessGuardPort.DeployResult result = guard.deploy(project);

        assertThat(result.preflightPassed()).as(result.preflightOutput()).isTrue();
    }

    /** T38 counterpart: when the script named inside a command string is genuinely missing,
     * the problem message names the script token, not the whole command line. */
    @Test
    void preflightNamesTheMissingScriptTokenFromACommandString(@TempDir Path project, @TempDir Path forgeideHome)
            throws IOException {
        assumePython3Available();
        Path harness = project.resolve(".gigacode");
        Files.createDirectories(harness.resolve("hooks"));
        Files.writeString(harness.resolve("hooks/settings.hooks.json"), """
                {"hooks": {"PreToolUse": [{"matcher": "Write|Edit", "hooks": [
                  {"type": "command", "command": "python3 hooks/missing-guard.py"}]}]}}
                """);
        DefaultHarnessGuard guard = new DefaultHarnessGuard(forgeideHome);

        HarnessGuardPort.DeployResult result = guard.deploy(project);

        assertThat(result.preflightPassed()).isFalse();
        assertThat(result.preflightOutput()).contains("not found: hooks/missing-guard.py");
    }

    /** T40/T41: hook commands template the project root as ${PROJECT_ROOT} and the interpreter as
     * ${PYTHON} (e.g. pprb-kid's "${PYTHON} ${PROJECT_ROOT}/.gigacode/hooks/guard.py") — the
     * resolver expands both at deploy time so the generated settings.json names a real path, and a
     * harness whose scripts are all present deploys cleanly. */
    @Test
    void deployResolvesPlaceholderHookCommandsAndPasses(@TempDir Path project, @TempDir Path forgeideHome)
            throws IOException {
        assumePython3Available();
        Path harness = project.resolve(".gigacode");
        Files.createDirectories(harness.resolve("hooks"));
        Files.writeString(harness.resolve("hooks/guard.py"), "print('guarding')\n");
        Files.writeString(harness.resolve("hooks/settings.hooks.json"), """
                {"hooks": {"PreToolUse": [{"matcher": "Write|Edit", "hooks": [
                  {"type": "command", "command": "${PYTHON} ${PROJECT_ROOT}/.gigacode/hooks/guard.py"}]}]}}
                """);
        DefaultHarnessGuard guard = new DefaultHarnessGuard(forgeideHome);

        HarnessGuardPort.DeployResult result = guard.deploy(project);

        assertThat(result.preflightPassed()).as(result.preflightOutput()).isTrue();
        // ${PROJECT_ROOT} is expanded to the real path in the generated settings.json.
        String resolved = Files.readString(harness.resolve("settings.json"));
        assertThat(resolved).contains(project.resolve(".gigacode/hooks/guard.py").toString());
        assertThat(resolved).doesNotContain("${PROJECT_ROOT}");
    }

    /** T40/T41 counterpart: a ${PROJECT_ROOT}-templated hook that genuinely doesn't exist still
     * fails preflight — after resolution the missing path is absolute, so the problem names it. */
    @Test
    void deployFailsWhenAPlaceholderHookIsMissing(@TempDir Path project, @TempDir Path forgeideHome)
            throws IOException {
        assumePython3Available();
        Path harness = project.resolve(".gigacode");
        Files.createDirectories(harness.resolve("hooks"));
        Files.writeString(harness.resolve("hooks/settings.hooks.json"), """
                {"hooks": {"PreToolUse": [{"matcher": "Write|Edit", "hooks": [
                  {"type": "command", "command": "${PYTHON} ${PROJECT_ROOT}/.gigacode/hooks/missing.py"}]}]}}
                """);
        DefaultHarnessGuard guard = new DefaultHarnessGuard(forgeideHome);

        HarnessGuardPort.DeployResult result = guard.deploy(project);

        assertThat(result.preflightPassed()).isFalse();
        assertThat(result.preflightOutput()).contains("missing.py");
    }

    @Test
    void deployingAHarnessWithAMissingReferencedHookFailsPreflight(@TempDir Path project, @TempDir Path forgeideHome)
            throws IOException {
        assumePython3Available();
        Path harness = project.resolve(".gigacode");
        Files.createDirectories(harness.resolve("hooks"));
        Files.writeString(harness.resolve("hooks/settings.hooks.json"), """
                {"hooks": {"SubagentStop": ["hooks/does-not-exist.py"]}}
                """);
        DefaultHarnessGuard guard = new DefaultHarnessGuard(forgeideHome);

        HarnessGuardPort.DeployResult result = guard.deploy(project);

        assertThat(result.preflightPassed()).isFalse();
        assertThat(result.preflightOutput()).contains("does-not-exist.py");
        assertThat(guard.preflightStatus(project).passed()).isFalse();
    }

    /** T41 self-heal (reverse of the old T32 move): deploy relocates a settings.hooks.json left at
     * the harness root (old ForgeIDE placement) to hooks/, where the forge resolver/preflight
     * expect it — a project deployed under the old convention deploys cleanly, the stale root copy
     * is gone, and the resolved settings.json is generated. */
    @Test
    void deployRelocatesRootSettingsTemplateUnderHooks(
            @TempDir Path project, @TempDir Path forgeideHome) throws IOException {
        assumePython3Available();
        Path harness = project.resolve(".gigacode");
        Files.createDirectories(harness);
        Files.writeString(harness.resolve("settings.hooks.json"), """
                {"hooks": {"SubagentStop": []}}
                """);
        DefaultHarnessGuard guard = new DefaultHarnessGuard(forgeideHome);

        HarnessGuardPort.DeployResult result = guard.deploy(project);

        assertThat(result.preflightPassed()).as(result.preflightOutput()).isTrue();
        assertThat(harness.resolve("hooks/settings.hooks.json")).isRegularFile();
        assertThat(harness.resolve("settings.hooks.json")).doesNotExist();
        assertThat(harness.resolve("settings.json")).isRegularFile();
    }

    /** T41: when the harness ships its own resolver, deploy runs it (not the bundled fallback) so
     * ForgeIDE behaves exactly like a forge deploy. Proven by a stand-in resolver that stamps a
     * recognizable marker into the settings.json it writes. */
    @Test
    void deployRunsTheHarnessOwnResolverWhenPresent(@TempDir Path project, @TempDir Path forgeideHome)
            throws IOException {
        assumePython3Available();
        Path harness = project.resolve(".gigacode");
        Files.createDirectories(harness.resolve("hooks"));
        Files.writeString(harness.resolve("hooks/settings.hooks.json"), """
                {"hooks": {"SubagentStop": []}}
                """);
        Files.writeString(harness.resolve("hooks/resolve_hook_paths.py"), """
                import sys, json, pathlib
                proj = sys.argv[sys.argv.index("--project") + 1]
                target = pathlib.Path(proj) / ".gigacode" / "settings.json"
                target.write_text(json.dumps({"hooks": {"SubagentStop": []}, "_marker": "harness-resolver"}))
                """);
        DefaultHarnessGuard guard = new DefaultHarnessGuard(forgeideHome);

        HarnessGuardPort.DeployResult result = guard.deploy(project);

        assertThat(result.preflightPassed()).as(result.preflightOutput()).isTrue();
        assertThat(Files.readString(harness.resolve("settings.json"))).contains("harness-resolver");
    }

    /** T41: when the harness ships its own preflight, deploy runs it and honours its richer exit
     * codes — exit 2 ("deployed, project not yet initialized") means enforcement is on, so a fresh
     * deploy counts as passed (preserves T37). */
    @Test
    void deployHonoursHarnessPreflightExitTwoAsPassed(@TempDir Path project, @TempDir Path forgeideHome)
            throws IOException {
        assumePython3Available();
        Path harness = project.resolve(".gigacode");
        Files.createDirectories(harness.resolve("hooks"));
        Files.writeString(harness.resolve("hooks/settings.hooks.json"), """
                {"hooks": {"SubagentStop": []}}
                """);
        Files.writeString(harness.resolve("hooks/preflight.py"), "import sys; print('init needed'); sys.exit(2)\n");
        DefaultHarnessGuard guard = new DefaultHarnessGuard(forgeideHome);

        HarnessGuardPort.DeployResult result = guard.deploy(project);

        assertThat(result.preflightPassed()).isTrue();
        assertThat(result.preflightOutput()).contains("init needed");
    }

    /** T41 counterpart: the harness preflight's exit 1 means enforcement off — that fails. */
    @Test
    void deployHonoursHarnessPreflightExitOneAsFailed(@TempDir Path project, @TempDir Path forgeideHome)
            throws IOException {
        assumePython3Available();
        Path harness = project.resolve(".gigacode");
        Files.createDirectories(harness.resolve("hooks"));
        Files.writeString(harness.resolve("hooks/settings.hooks.json"), """
                {"hooks": {"SubagentStop": []}}
                """);
        Files.writeString(harness.resolve("hooks/preflight.py"), "import sys; print('enforcement off'); sys.exit(1)\n");
        DefaultHarnessGuard guard = new DefaultHarnessGuard(forgeideHome);

        HarnessGuardPort.DeployResult result = guard.deploy(project);

        assertThat(result.preflightPassed()).isFalse();
        assertThat(result.preflightOutput()).contains("enforcement off");
    }

    @Test
    void noDriftRightAfterDeploy(@TempDir Path project, @TempDir Path forgeideHome) throws IOException {
        assumePython3Available();
        writeValidHarness(project);
        DefaultHarnessGuard guard = new DefaultHarnessGuard(forgeideHome);
        guard.deploy(project);

        assertThat(guard.checkDrift(project)).isEmpty();
    }

    @Test
    void editingAHookFileOutsideTheIdeTripsDriftWithADiff(@TempDir Path project, @TempDir Path forgeideHome)
            throws IOException {
        assumePython3Available();
        writeValidHarness(project);
        DefaultHarnessGuard guard = new DefaultHarnessGuard(forgeideHome);
        guard.deploy(project);

        // Т-7's scenario: something other than the IDE edits the vendored hook in place.
        Files.writeString(project.resolve(".gigacode/hooks/tdd-guard.py"), "print('tampered')\n");

        Optional<HarnessGuardPort.Drift> drift = guard.checkDrift(project);

        assertThat(drift).isPresent();
        assertThat(drift.get().diff()).contains("modified: hooks/tdd-guard.py");
    }

    @Test
    void acceptingDriftAdoptsTheCurrentContentAsTheNewBaseline(@TempDir Path project, @TempDir Path forgeideHome)
            throws IOException {
        assumePython3Available();
        writeValidHarness(project);
        DefaultHarnessGuard guard = new DefaultHarnessGuard(forgeideHome);
        guard.deploy(project);
        Files.writeString(project.resolve(".gigacode/hooks/tdd-guard.py"), "print('now legitimate')\n");
        assertThat(guard.checkDrift(project)).isPresent();

        guard.acceptDrift(project);

        assertThat(guard.checkDrift(project)).isEmpty();
    }

    @Test
    void rollingBackDriftRestoresTheOriginalContentFromTheCache(@TempDir Path project, @TempDir Path forgeideHome)
            throws IOException {
        assumePython3Available();
        writeValidHarness(project);
        String original = Files.readString(project.resolve(".gigacode/hooks/tdd-guard.py"));
        DefaultHarnessGuard guard = new DefaultHarnessGuard(forgeideHome);
        guard.deploy(project);
        Files.writeString(project.resolve(".gigacode/hooks/tdd-guard.py"), "print('tampered')\n");

        List<String> restored = guard.rollbackDrift(project);

        assertThat(restored).contains("hooks/tdd-guard.py");
        assertThat(Files.readString(project.resolve(".gigacode/hooks/tdd-guard.py"))).isEqualTo(original);
        assertThat(guard.checkDrift(project)).isEmpty();
    }

    @Test
    void rollbackAlsoDeletesAFileAddedOutsideTheBaseline(@TempDir Path project, @TempDir Path forgeideHome)
            throws IOException {
        assumePython3Available();
        writeValidHarness(project);
        DefaultHarnessGuard guard = new DefaultHarnessGuard(forgeideHome);
        guard.deploy(project);
        Path planted = project.resolve(".gigacode/hooks/backdoor.py");
        Files.writeString(planted, "print('surprise')\n");

        List<String> restored = guard.rollbackDrift(project);

        assertThat(restored).contains("hooks/backdoor.py");
        assertThat(planted).doesNotExist();
    }

    @Test
    void resolveFromCacheRewritesOnlyHarnessPathsAndRunsTheCacheCopy(@TempDir Path project, @TempDir Path forgeideHome)
            throws IOException {
        assumePython3Available();
        writeValidHarness(project);
        DefaultHarnessGuard guard = new DefaultHarnessGuard(forgeideHome);
        HarnessGuardPort.DeployResult deployed = guard.deploy(project);

        List<String> resolved = guard.resolveFromCache(project,
                List.of("python3", ".gigacode/hooks/tdd-guard.py", "--flag"));

        Path expectedCachePath = forgeideHome.resolve("harness-cache").resolve(deployed.hash())
                .resolve("hooks").resolve("tdd-guard.py");
        assertThat(resolved).containsExactly("python3", expectedCachePath.toString(), "--flag");
    }

    @Test
    void resolveFromCacheLeavesNonHarnessTokensUntouched(@TempDir Path project, @TempDir Path forgeideHome)
            throws IOException {
        assumePython3Available();
        writeValidHarness(project);
        DefaultHarnessGuard guard = new DefaultHarnessGuard(forgeideHome);
        guard.deploy(project);

        List<String> resolved = guard.resolveFromCache(project, List.of("python3", "scripts/check_coverage.py"));

        assertThat(resolved).containsExactly("python3", "scripts/check_coverage.py");
    }

    @Test
    void editWritesTheWorkingCopyTheCacheAndTheBaselineTogether(@TempDir Path project, @TempDir Path forgeideHome)
            throws IOException {
        assumePython3Available();
        writeValidHarness(project);
        DefaultHarnessGuard guard = new DefaultHarnessGuard(forgeideHome);
        guard.deploy(project);

        HarnessGuardPort.HarnessEditResult result = guard.edit(project, "hooks/tdd-guard.py", "print('edited via ide')\n");

        assertThat(result.oldHash()).isNotEqualTo(result.newHash());
        assertThat(Files.readString(project.resolve(".gigacode/hooks/tdd-guard.py"))).isEqualTo("print('edited via ide')\n");
        // FR-8.3: a save through the trusted path must never itself register as drift.
        assertThat(guard.checkDrift(project)).isEmpty();
    }

    /** Т-7/SR-8's own acceptance scenario, against the real vendored {@code tdd-guard.py} (T15's
     * NFR-6 fixture, {@code ForgeHooksContractTest}) instead of a synthetic stand-in: an edit to
     * that exact file must trip drift. */
    @Test
    void editingTheRealVendoredTddGuardHookTripsDrift(@TempDir Path project, @TempDir Path forgeideHome) throws Exception {
        assumePython3Available();
        Path harness = project.resolve(".gigacode");
        Files.createDirectories(harness.resolve("hooks"));
        Files.copy(vendoredHook("tdd-guard.py"), harness.resolve("hooks/tdd-guard.py"), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(harness.resolve("hooks/settings.hooks.json"), """
                {"hooks": {"SubagentStop": ["hooks/tdd-guard.py"]}}
                """);
        DefaultHarnessGuard guard = new DefaultHarnessGuard(forgeideHome);
        assertThat(guard.deploy(project).preflightPassed()).isTrue();

        Files.writeString(harness.resolve("hooks/tdd-guard.py"),
                Files.readString(harness.resolve("hooks/tdd-guard.py")) + "\n# tampered by the agent phase\n");

        assertThat(guard.checkDrift(project)).isPresent();
    }

    /** T34's acceptance: the importer copies a skill's whole directory into {@code
     * .gigacode/skills/<id>/...}, so a file that only exists to be referenced from SKILL.md's prose
     * still has to trip the same drift detection any other harness file gets. */
    @Test
    void editingAReferencesFileUnderASkillDirTripsDrift(@TempDir Path project, @TempDir Path forgeideHome)
            throws IOException {
        assumePython3Available();
        Path harness = project.resolve(".gigacode");
        Files.createDirectories(harness.resolve("skills/demo-skill/references"));
        Files.createDirectories(harness.resolve("hooks"));
        Files.writeString(harness.resolve("skills/demo-skill/SKILL.md"),
                "---\nname: demo-skill\n---\n\nSee references/notes.md\n");
        Files.writeString(harness.resolve("skills/demo-skill/references/notes.md"), "original notes\n");
        Files.writeString(harness.resolve("hooks/settings.hooks.json"), """
                {"hooks": {"SubagentStop": []}}
                """);
        DefaultHarnessGuard guard = new DefaultHarnessGuard(forgeideHome);
        assertThat(guard.deploy(project).preflightPassed()).isTrue();

        Files.writeString(harness.resolve("skills/demo-skill/references/notes.md"), "tampered notes\n");

        Optional<HarnessGuardPort.Drift> drift = guard.checkDrift(project);

        assertThat(drift).isPresent();
        assertThat(drift.get().diff()).contains("modified: skills/demo-skill/references/notes.md");
    }

    private static Path vendoredHook(String name) throws URISyntaxException {
        URL resource = DefaultHarnessGuardTest.class.getResource("/forge-fixtures/hooks/" + name);
        if (resource == null) {
            throw new IllegalStateException("vendored hook not found on classpath: " + name);
        }
        return Paths.get(resource.toURI());
    }

    private static void writeValidHarness(Path project) throws IOException {
        Path harness = project.resolve(".gigacode");
        Files.createDirectories(harness.resolve("hooks"));
        Files.writeString(harness.resolve("hooks/tdd-guard.py"), "print('guarding')\n");
        Files.writeString(harness.resolve("hooks/settings.hooks.json"), """
                {"hooks": {"SubagentStop": ["hooks/tdd-guard.py"]}}
                """);
    }

    private static void assumePython3Available() {
        try {
            Process p = new ProcessBuilder("python3", "--version").start();
            assumeTrue(p.waitFor() == 0, "python3 binary not available");
        } catch (IOException | InterruptedException e) {
            assumeTrue(false, "python3 binary not available");
        }
    }
}
