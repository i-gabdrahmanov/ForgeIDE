package dev.forgeide.runtime.agent;

import dev.forgeide.core.port.AgentInvocation;
import dev.forgeide.core.port.AgentResult;
import dev.forgeide.core.project.RuntimeBinding;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real end-to-end smoke test (T09 acceptance criterion: "реальный smoke-вызов хотя бы одного
 * рантайма («ответь OK») проходит end-to-end"). Skips — does not fail — on any machine
 * without the {@code claude} CLI installed, so it degrades gracefully off this environment.
 */
class ClaudeAgentRuntimeSmokeTest {

    private static final Path CLAUDE_BINARY = Path.of("/opt/homebrew/bin/claude");

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void realClaudeCliAnswersOkInsideTheContractJson(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(Files.isExecutable(CLAUDE_BINARY),
                "claude CLI not installed at " + CLAUDE_BINARY + "; skipping real smoke test");

        String prompt = "Reply with exactly this JSON and nothing else (no markdown fences, "
                + "no extra text): {\"step_id\":\"smoke\",\"status\":\"done\",\"artifacts\":[],"
                + "\"pending_questions\":[],\"summary\":\"OK\"}";
        RuntimeBinding binding = new RuntimeBinding("claude", CLAUDE_BINARY, List.of());
        // ProcessGroupLauncher wipes the child's env entirely by design (SR-1/SR-5: never
        // inherit the IDE's ambient env) — real invocations get an explicit whitelist from
        // env_scope (T16, out of scope here). This CLI's login/session state on this machine
        // depends on more of the ambient environment than just HOME/PATH (keychain/session
        // plumbing this test isn't in the business of reverse-engineering), so — only for this
        // one real-binary smoke test, whose job is proving the stream-json plumbing end to
        // end, not exercising env-scoping — pass the full ambient environment through.
        AgentInvocation invocation = new AgentInvocation(dir, prompt, Duration.ofSeconds(50),
                50_000, 64L * 1024 * 1024, dir.resolve("logs"), binding, System.getenv());

        AgentResult result = new ClaudeAgentRuntime().execute(invocation, event -> { });

        assertThat(result.finalJson()).isPresent();
        assertThat(result.finalJson().get().get("step_id").asText()).isEqualTo("smoke");
        assertThat(invocation.logDir().resolve("meta.json")).exists();
    }
}
