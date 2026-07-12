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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Exercises {@link DefaultHarnessGuard} (SDD FR-1.4/FR-8.3/SR-7/SR-8) against a real filesystem
 * and the bundled {@code deploy.sh}/{@code preflight.py} wrappers.
 */
class DefaultHarnessGuardTest {

    @Test
    void preflightIsNotPassedBeforeAnyDeploy(@TempDir Path project, @TempDir Path forgeideHome) {
        DefaultHarnessGuard guard = new DefaultHarnessGuard(forgeideHome);

        HarnessGuardPort.PreflightStatus status = guard.preflightStatus(project);

        assertThat(status.passed()).isFalse();
        assertThat(status.detail()).contains("not deployed");
    }

    @Test
    void deployingAValidHarnessPasses(@TempDir Path project, @TempDir Path forgeideHome) throws IOException {
        assumePython3Available();
        writeValidHarness(project);
        DefaultHarnessGuard guard = new DefaultHarnessGuard(forgeideHome);

        HarnessGuardPort.DeployResult result = guard.deploy(project);

        assertThat(result.preflightPassed()).isTrue();
        assertThat(guard.preflightStatus(project).passed()).isTrue();
        assertThat(forgeideHome.resolve("harness-cache").resolve(result.hash())
                .resolve("hooks").resolve("tdd-guard.py")).exists();
    }

    @Test
    void deployingAHarnessWithAMissingReferencedHookFailsPreflight(@TempDir Path project, @TempDir Path forgeideHome)
            throws IOException {
        assumePython3Available();
        Path harness = project.resolve(".gigacode");
        Files.createDirectories(harness.resolve("hooks"));
        Files.writeString(harness.resolve("settings.hooks.json"), """
                {"hooks": {"SubagentStop": ["hooks/does-not-exist.py"]}}
                """);
        DefaultHarnessGuard guard = new DefaultHarnessGuard(forgeideHome);

        HarnessGuardPort.DeployResult result = guard.deploy(project);

        assertThat(result.preflightPassed()).isFalse();
        assertThat(result.preflightOutput()).contains("does-not-exist.py");
        assertThat(guard.preflightStatus(project).passed()).isFalse();
    }

    @Test
    void deployingAHarnessWithSettingsAtTheOldHooksLocationFailsPreflightWithAMigrationMessage(
            @TempDir Path project, @TempDir Path forgeideHome) throws IOException {
        assumePython3Available();
        Path harness = project.resolve(".gigacode");
        Files.createDirectories(harness.resolve("hooks"));
        Files.writeString(harness.resolve("hooks/settings.hooks.json"), """
                {"hooks": {"SubagentStop": []}}
                """);
        DefaultHarnessGuard guard = new DefaultHarnessGuard(forgeideHome);

        HarnessGuardPort.DeployResult result = guard.deploy(project);

        assertThat(result.preflightPassed()).isFalse();
        assertThat(result.preflightOutput())
                .contains("hooks/settings.hooks.json")
                .contains("move it to")
                .contains("settings.hooks.json");
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
        Files.writeString(harness.resolve("settings.hooks.json"), """
                {"hooks": {"SubagentStop": ["hooks/tdd-guard.py"]}}
                """);
        DefaultHarnessGuard guard = new DefaultHarnessGuard(forgeideHome);
        assertThat(guard.deploy(project).preflightPassed()).isTrue();

        Files.writeString(harness.resolve("hooks/tdd-guard.py"),
                Files.readString(harness.resolve("hooks/tdd-guard.py")) + "\n# tampered by the agent phase\n");

        assertThat(guard.checkDrift(project)).isPresent();
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
        Files.writeString(harness.resolve("settings.hooks.json"), """
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
