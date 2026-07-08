package dev.forgeide.core.run;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RunLogLayoutTest {

    @Test
    void buildsTheGroundAiLogsPathConvention(@TempDir Path projectRoot) {
        Path dir = RunLogLayout.stepLogDir(projectRoot, "feature-x", "lite-red", 3);

        assertThat(dir).isEqualTo(projectRoot.resolve("ground").resolve("ai-logs")
                .resolve("feature-x").resolve("iter-3").resolve("lite-red"));
    }

    @Test
    void stepLogDirNestsUnderFeatureLogRoot(@TempDir Path projectRoot) {
        Path root = RunLogLayout.featureLogRoot(projectRoot, "feature-x");
        Path dir = RunLogLayout.stepLogDir(projectRoot, "feature-x", "lite-red", 3);

        assertThat(dir).isEqualTo(root.resolve("iter-3").resolve("lite-red"));
    }
}
