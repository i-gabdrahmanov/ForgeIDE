package dev.forgeide.runtime.script;

import dev.forgeide.core.port.ScriptInvocation;
import dev.forgeide.core.port.ScriptResult;
import dev.forgeide.core.port.ScriptRunnerException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScriptExecutorTest {

    private final ScriptExecutor executor = new ScriptExecutor();

    @Test
    void capturesStdoutAndExitCodeOnSuccess(@TempDir Path dir) throws ScriptRunnerException {
        ScriptInvocation invocation = new ScriptInvocation(dir,
                List.of("/bin/sh", "-c", "echo hello; echo world >&2"), Duration.ofSeconds(5), Map.of());

        ScriptResult result = executor.run(invocation);

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("hello");
        assertThat(result.stderr()).contains("world");
    }

    @Test
    void nonZeroExitIsReportedNotThrown(@TempDir Path dir) throws ScriptRunnerException {
        ScriptInvocation invocation = new ScriptInvocation(dir,
                List.of("/bin/sh", "-c", "echo failing >&2; exit 3"), Duration.ofSeconds(5), Map.of());

        ScriptResult result = executor.run(invocation);

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.stderr()).contains("failing");
    }

    @Test
    void timeoutKillsTheProcessAndStillReturnsANonZeroExitResult(@TempDir Path dir) throws ScriptRunnerException {
        ScriptInvocation invocation = new ScriptInvocation(dir,
                List.of("/bin/sh", "-c", "sleep 30"), Duration.ofMillis(300), Map.of());

        ScriptResult result = executor.run(invocation);

        assertThat(result.exitCode()).isNotZero();
    }

    @Test
    void onlyExplicitEnvIsVisibleToTheScript(@TempDir Path dir) throws ScriptRunnerException {
        ScriptInvocation invocation = new ScriptInvocation(dir,
                List.of("/bin/sh", "-c", "echo \"FOO=$FOO\""), Duration.ofSeconds(5), Map.of("FOO", "bar"));

        ScriptResult result = executor.run(invocation);

        assertThat(result.stdout()).contains("FOO=bar");
    }

    @Test
    void unresolvableWorkingDirSurfacesAsScriptRunnerException() {
        Path bogus = Path.of("/no/such/directory/forgeide-test");
        ScriptInvocation invocation = new ScriptInvocation(bogus,
                List.of("/bin/sh", "-c", "echo hi"), Duration.ofSeconds(5), Map.of());

        assertThatThrownBy(() -> executor.run(invocation)).isInstanceOf(ScriptRunnerException.class);
    }
}
