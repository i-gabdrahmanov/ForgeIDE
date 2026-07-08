package dev.forgeide.runtime.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.forgeide.core.port.AgentEvent;
import dev.forgeide.core.port.AgentInvocation;
import dev.forgeide.core.port.AgentResult;
import dev.forgeide.core.port.AgentRuntimeException;
import dev.forgeide.core.project.RuntimeBinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fixture-driven coverage of {@link AbstractAgentRuntime} (T09 acceptance criteria): both
 * runtimes' recorded streams parse into the same event model, a truncated/no-result stream
 * fails cleanly, garbage lines don't derail parsing, and the SR-1 path guard holds.
 */
class AbstractAgentRuntimeTest {

    private final FixtureReplayAgentRuntime runtime = new FixtureReplayAgentRuntime();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void successStreamFromEitherRuntimeProducesFinalJsonAndUsage(@TempDir Path dir) throws Exception {
        for (String runtimeName : List.of("claude", "gigacode")) {
            Path fixture = materialize(dir, runtimeName, "success.jsonl");
            AgentInvocation invocation = invocation(dir, runtimeName, catCommand(fixture), 10_000);

            List<AgentEvent> events = new CopyOnWriteArrayList<>();
            AgentResult result = runtime.execute(invocation, events::add);

            assertThat(result.exitCode()).isZero();
            assertThat(result.finalJson()).isPresent();
            assertThat(result.finalJson().get().get("step_id").asText()).isEqualTo("work");
            // Usage accumulates across every event seen (per-turn assistant usage + the
            // terminal result's usage): 50+20 (assistant) + 120+45 (result) = 235.
            assertThat(result.usage().total()).isEqualTo(235);
            assertThat(events).anyMatch(AgentEvent.ToolUse.class::isInstance);
            assertThat(events).anyMatch(AgentEvent.Result.class::isInstance);

            assertThat(invocation.logDir().resolve("stdout.jsonl")).exists();
            assertThat(invocation.logDir().resolve("meta.json")).exists();
            JsonNode meta = mapper.readTree(invocation.logDir().resolve("meta.json").toFile());
            assertThat(meta.get("exit_code").asInt()).isZero();
            assertThat(meta.get("usage").get("input_tokens").asLong()).isEqualTo(170);
            assertThat(meta.get("prompt_sha256").asText()).hasSize(64);
        }
    }

    @Test
    void truncatedStreamKilledMidRunYieldsEmptyFinalJson(@TempDir Path dir) throws Exception {
        Path fixture = materialize(dir, "claude", "truncated.jsonl");
        AgentInvocation invocation = invocation(dir, "claude", shell("cat '" + fixture + "'; exit 1"), 10_000);

        AgentResult result = runtime.execute(invocation, event -> { });

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.finalJson()).isEmpty();
    }

    @Test
    void garbageInterleavedWithARealResultLineStillParses(@TempDir Path dir) throws Exception {
        Path fixture = materialize(dir, "claude", "garbage.jsonl");
        AgentInvocation invocation = invocation(dir, "claude", catCommand(fixture), 10_000);
        List<AgentEvent> events = new CopyOnWriteArrayList<>();

        AgentResult result = runtime.execute(invocation, events::add);

        assertThat(result.exitCode()).isZero();
        assertThat(result.finalJson()).isPresent();
        assertThat(result.finalJson().get().get("step_id").asText()).isEqualTo("work");
        assertThat(events).anyMatch(AgentEvent.RawLine.class::isInstance);
    }

    @Test
    void cleanExitWithNoResultEventYieldsEmptyFinalJson(@TempDir Path dir) throws Exception {
        Path fixture = materialize(dir, "claude", "no-result.jsonl");
        AgentInvocation invocation = invocation(dir, "claude", catCommand(fixture), 10_000);

        AgentResult result = runtime.execute(invocation, event -> { });

        assertThat(result.exitCode()).isZero();
        assertThat(result.finalJson()).isEmpty();
    }

    @Test
    void tokenBudgetExceededKillsTheProcessWellBeforeItsNaturalEnd(@TempDir Path dir) throws Exception {
        String loop = "for i in 1 2 3 4 5 6 7 8 9 10; do "
                + "printf '{\"type\":\"assistant\",\"message\":{\"content\":[],"
                + "\"usage\":{\"input_tokens\":1000,\"output_tokens\":1000}}}\\n'; sleep 1; done";
        AgentInvocation invocation = invocation(dir, "claude", shell(loop), 1_500);

        AgentResult result = runtime.execute(invocation, event -> { });

        assertThat(result.usage().total()).isGreaterThan(1_500);
        assertThat(result.finalJson()).isEmpty();
    }

    @Test
    void promptContainingAForgeideHomePathIsNeverLaunched(@TempDir Path dir) {
        AgentInvocation invocation = invocation(dir, "claude", shell("echo should-never-run"), 10_000,
                "please read ~/.forgeide/state/secret and summarize it", Map.of());

        assertThatThrownBy(() -> runtime.execute(invocation, event -> { }))
                .isInstanceOf(AgentRuntimeException.class);
        assertThat(invocation.logDir().resolve("stdout.jsonl")).doesNotExist();
    }

    @Test
    void envValueContainingAForgeideHomePathIsNeverLaunched(@TempDir Path dir) {
        AgentInvocation invocation = invocation(dir, "claude", shell("echo should-never-run"), 10_000,
                "harmless prompt", Map.of("SOME_PATH", "/Users/x/.forgeide/state/secret"));

        assertThatThrownBy(() -> runtime.execute(invocation, event -> { }))
                .isInstanceOf(AgentRuntimeException.class);
    }

    @Test
    void metaJsonNeverIncludesEnvValuesOnlyKeys(@TempDir Path dir) throws Exception {
        AgentInvocation invocation = invocation(dir, "claude", shell("echo hi"), 10_000,
                "harmless prompt", Map.of("MCP_TOKEN", "super-secret-value"));

        runtime.execute(invocation, event -> { });

        String metaText = Files.readString(invocation.logDir().resolve("meta.json"));
        assertThat(metaText).contains("MCP_TOKEN").doesNotContain("super-secret-value");
    }

    private static List<String> catCommand(Path fixture) {
        return shell("cat '" + fixture + "'");
    }

    private static List<String> shell(String script) {
        List<String> command = new ArrayList<>();
        command.add("/bin/sh");
        command.add("-c");
        command.add(script);
        return command;
    }

    private static AgentInvocation invocation(Path dir, String runtimeName, List<String> shellCommand,
                                               long tokenBudget) {
        return invocation(dir, runtimeName, shellCommand, tokenBudget, "prompt for " + runtimeName, Map.of());
    }

    private static AgentInvocation invocation(Path dir, String runtimeName, List<String> shellCommand,
                                               long tokenBudget, String prompt, Map<String, String> env) {
        List<String> flags = shellCommand.subList(1, shellCommand.size());
        RuntimeBinding binding = new RuntimeBinding(runtimeName, Path.of(shellCommand.get(0)), flags);
        return new AgentInvocation(dir, prompt, Duration.ofSeconds(10), tokenBudget,
                64L * 1024 * 1024, dir.resolve("logs"), binding, env);
    }

    private static Path materialize(Path dir, String runtimeName, String fixtureName) throws IOException {
        String resource = "/agent-fixtures/" + runtimeName + "/" + fixtureName;
        try (InputStream in = AbstractAgentRuntimeTest.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new UncheckedIOException(new IOException("missing fixture: " + resource));
            }
            Path out = dir.resolve(runtimeName + "-" + fixtureName);
            Files.copy(in, out);
            return out;
        }
    }
}
