package dev.forgeide.core.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.forgeide.core.engine.support.FixtureAgentRuntimePort;
import dev.forgeide.core.engine.support.FixtureScriptRunnerPort;
import dev.forgeide.core.engine.support.InMemoryStateStore;
import dev.forgeide.core.engine.support.TestProjects;
import dev.forgeide.core.pipeline.AgentStep;
import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.policy.RetryPolicy;
import dev.forgeide.core.policy.TokenBudget;
import dev.forgeide.core.port.AgentResult;
import dev.forgeide.core.port.ManifestProjectorPort;
import dev.forgeide.core.port.ScopeDiffPort;
import dev.forgeide.core.port.SecretStorePort;
import dev.forgeide.core.port.TokenUsage;
import dev.forgeide.core.run.RunId;
import dev.forgeide.core.run.RunStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static dev.forgeide.core.engine.support.Await.until;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * T16 acceptance (SR-5/Т-11): "секрет из env_scope одной фазы отсутствует в env соседней" — two
 * independent agent steps in the same run, one whose {@code env_scope} names {@code GIT_TOKEN}
 * and one with an empty {@code env_scope}, must each see exactly what their own {@code env_scope}
 * declares — never the other's secret, never the full store.
 */
class PipelineEngineEnvScopeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TokenBudget BUDGET = new TokenBudget(1_000, Duration.ofMinutes(5), 10);

    @Test
    void secretIsInjectedOnlyIntoTheStepThatDeclaresItInEnvScope(@TempDir Path repo) throws IOException {
        Path promptDir = repo.resolve("prompts");
        Files.createDirectories(promptDir);
        Files.writeString(promptDir.resolve("with-token.md"), "with-token");
        Files.writeString(promptDir.resolve("without-token.md"), "without-token");

        AgentStep withToken = new AgentStep("with-token", List.of(), "claude",
                Path.of("prompts/with-token.md"), List.of(), List.of(), List.of("GIT_TOKEN"),
                RetryPolicy.DEFAULT, BUDGET);
        AgentStep withoutToken = new AgentStep("without-token", List.of(), "claude",
                Path.of("prompts/without-token.md"), List.of(), List.of(), List.of(),
                RetryPolicy.DEFAULT, BUDGET);
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(withToken, withoutToken));

        Map<String, Map<String, String>> envSeenByStep = new ConcurrentHashMap<>();
        FixtureAgentRuntimePort agentRuntime = new FixtureAgentRuntimePort(invocation -> {
            envSeenByStep.put(invocation.prompt(), invocation.env());
            ObjectNode json = MAPPER.createObjectNode();
            json.put("step_id", invocation.prompt());
            return new AgentResult(0, Optional.of(json), new TokenUsage(1, 1), Path.of("raw.log"));
        });

        SecretStorePort secretStore = envScope -> envScope.contains("GIT_TOKEN")
                ? Map.of("GIT_TOKEN", "s3cr3t")
                : Map.of();

        InMemoryStateStore stateStore = new InMemoryStateStore();
        try (PipelineEngine engine = new PipelineEngine(stateStore, agentRuntime, FixtureScriptRunnerPort.alwaysOk(),
                ManifestProjectorPort.NOOP, ScopeDiffPort.NOOP, secretStore)) {
            RunId runId = engine.start(TestProjects.minimal(repo), definition, "feature-x");

            until(() -> engine.snapshot(runId).map(s -> s.status() == RunStatus.COMPLETED).orElse(false));
        }

        assertThat(envSeenByStep.get("with-token")).containsExactly(Map.entry("GIT_TOKEN", "s3cr3t"));
        assertThat(envSeenByStep.get("without-token")).isEmpty();
    }
}
