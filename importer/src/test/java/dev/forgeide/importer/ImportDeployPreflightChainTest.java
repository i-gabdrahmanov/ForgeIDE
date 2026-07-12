package dev.forgeide.importer;

import dev.forgeide.core.pipeline.yaml.PipelineTemplates;
import dev.forgeide.core.port.HarnessGuardPort;
import dev.forgeide.importer.scaffold.ScaffoldCatalog;
import dev.forgeide.importer.scaffold.ScaffoldScanner;
import dev.forgeide.runtime.harness.DefaultHarnessGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * T38 — the full «импорт реальной обвязки → deploy → preflight ok без ручных действий» chain
 * (T32's and T37's headline acceptance) across the importer→runtime boundary, which no other
 * test crosses: {@link ImportEndToEndTest} stops at file layout, and the runtime harness tests
 * hand-craft their harness instead of importing one. Exactly this blind spot let a freshly
 * imported project fail preflight (hook scripts didn't travel; the command string
 * {@code "python3 hooks/tdd-guard.py"} was checked as one path) while every module's own suite
 * stayed green.
 */
class ImportDeployPreflightChainTest {

    @Test
    void importedSampleScaffoldDeploysAndPassesPreflightWithoutManualSteps(
            @TempDir Path projectRoot, @TempDir Path forgeideHome) throws IOException {
        assumePython3Available();
        ScaffoldCatalog catalog = ScaffoldScanner.scan(fixture("sample-scaffold"));
        ImportResult result = new ImportSession(PipelineTemplates.forgelite(), catalog).result();
        ImportWriter.write(projectRoot, result);

        HarnessGuardPort.DeployResult deployed = new DefaultHarnessGuard(forgeideHome).deploy(projectRoot);

        assertThat(deployed.preflightPassed())
                .as("preflight must pass on a freshly imported project, output:\n%s",
                        deployed.preflightOutput())
                .isTrue();
    }

    private static void assumePython3Available() {
        try {
            Process p = new ProcessBuilder("python3", "--version").start();
            assumeTrue(p.waitFor() == 0, "python3 not available");
        } catch (IOException | InterruptedException e) {
            assumeTrue(false, "python3 not available");
        }
    }

    private static Path fixture(String name) {
        try {
            return Paths.get(ImportDeployPreflightChainTest.class
                    .getResource("/dev/forgeide/importer/fixtures/" + name).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
