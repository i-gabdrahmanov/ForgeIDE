package dev.forgeide.runtime.agent;

import dev.forgeide.core.port.AgentInvocation;
import dev.forgeide.core.project.RuntimeBinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T38: pins the exact CLI shape SD §6's table promises ({@code claude -p --output-format
 * stream-json --verbose}, prompt via stdin so {@code -p} has no positional argument) without
 * needing the real binary. {@link ClaudeAgentRuntimeSmokeTest} skips wherever {@code claude}
 * isn't installed — CI among them — and T26's runtime coverage floor must not hang off a test
 * that skips there: that exact combination kept every CI run red (82.75% < 83% once the smoke
 * test's lines dropped out).
 */
class ClaudeAgentRuntimeTest {

    @Test
    void buildsTheStreamJsonCommandLineFromTheRuntimeBinding(@TempDir Path dir) {
        RuntimeBinding binding = new RuntimeBinding("claude", Path.of("/opt/tools/claude"),
                List.of("--allowedTools", "Edit"));
        AgentInvocation invocation = new AgentInvocation(dir, "prompt travels via stdin",
                Duration.ofSeconds(5), 1_000, 1024, dir.resolve("logs"), binding, Map.of());

        List<String> command = new ClaudeAgentRuntime().buildCommand(invocation);

        assertThat(command).containsExactly("/opt/tools/claude", "--allowedTools", "Edit",
                "-p", "--output-format", "stream-json", "--verbose");
    }
}
