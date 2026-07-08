package dev.forgeide.runtime.project;

import dev.forgeide.core.project.RuntimeAvailability;
import dev.forgeide.core.project.RuntimeBinding;
import dev.forgeide.core.project.RuntimeStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessRuntimeAvailabilityCheckerTest {

    private final ProcessRuntimeAvailabilityChecker checker = new ProcessRuntimeAvailabilityChecker();

    @Test
    void availableWhenVersionExitsZero(@TempDir Path dir) throws IOException {
        Path script = script(dir, "ok.sh", "#!/bin/sh\necho 'runtime v1.2.3'\nexit 0\n");
        RuntimeBinding binding = new RuntimeBinding("claude", script);

        RuntimeAvailability result = checker.check(binding);

        assertThat(result.status()).isEqualTo(RuntimeStatus.AVAILABLE);
        assertThat(result.detail()).contains("v1.2.3");
    }

    @Test
    void unavailableWhenVersionExitsNonZero(@TempDir Path dir) throws IOException {
        Path script = script(dir, "fail.sh", "#!/bin/sh\necho 'boom' >&2\nexit 1\n");
        RuntimeBinding binding = new RuntimeBinding("claude", script);

        RuntimeAvailability result = checker.check(binding);

        assertThat(result.status()).isEqualTo(RuntimeStatus.UNAVAILABLE);
        assertThat(result.detail()).contains("exited 1");
    }

    @Test
    void unavailableWhenBinaryDoesNotExist(@TempDir Path dir) {
        RuntimeBinding binding = new RuntimeBinding("ghost", dir.resolve("nope"));

        RuntimeAvailability result = checker.check(binding);

        assertThat(result.status()).isEqualTo(RuntimeStatus.UNAVAILABLE);
        assertThat(result.detail()).contains("not found");
    }

    @Test
    void unavailableWhenFileIsNotExecutable(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("not-executable");
        Files.writeString(file, "not a script");
        RuntimeBinding binding = new RuntimeBinding("claude", file);

        RuntimeAvailability result = checker.check(binding);

        assertThat(result.status()).isEqualTo(RuntimeStatus.UNAVAILABLE);
    }

    @Test
    void unavailableWhenProcessHangsPastTimeout(@TempDir Path dir) throws IOException {
        Path script = script(dir, "hang.sh", "#!/bin/sh\nsleep 30\n");
        RuntimeBinding binding = new RuntimeBinding("claude", script);
        ProcessRuntimeAvailabilityChecker shortTimeout =
                new ProcessRuntimeAvailabilityChecker(Duration.ofMillis(200));

        RuntimeAvailability result = shortTimeout.check(binding);

        assertThat(result.status()).isEqualTo(RuntimeStatus.UNAVAILABLE);
        assertThat(result.detail()).contains("did not finish");
    }

    private static Path script(Path dir, String name, String content) throws IOException {
        Path script = dir.resolve(name);
        Files.writeString(script, content);
        script.toFile().setExecutable(true);
        return script;
    }
}
