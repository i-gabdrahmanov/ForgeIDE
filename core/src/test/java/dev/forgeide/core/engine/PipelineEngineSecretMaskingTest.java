package dev.forgeide.core.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.forgeide.core.audit.AuditEvent;
import dev.forgeide.core.engine.support.FixtureAgentRuntimePort;
import dev.forgeide.core.engine.support.FixtureScriptRunnerPort;
import dev.forgeide.core.engine.support.InMemoryStateStore;
import dev.forgeide.core.engine.support.TestProjects;
import dev.forgeide.core.pipeline.AgentStep;
import dev.forgeide.core.pipeline.JudgeStep;
import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.ScriptStep;
import dev.forgeide.core.policy.FailPolicy;
import dev.forgeide.core.policy.RetryPolicy;
import dev.forgeide.core.policy.TokenBudget;
import dev.forgeide.core.port.AgentResult;
import dev.forgeide.core.port.ManifestProjectorPort;
import dev.forgeide.core.port.ScopeDiffPort;
import dev.forgeide.core.port.ScriptResult;
import dev.forgeide.core.port.SecretStorePort;
import dev.forgeide.core.port.TokenUsage;
import dev.forgeide.core.run.RunId;
import dev.forgeide.core.run.RunStatus;
import dev.forgeide.core.run.StepSnapshot;
import dev.forgeide.core.secret.SecretMasker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.forgeide.core.engine.support.Await.until;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * T27 acceptance: "секрет, отпечатанный check-скриптом в stdout, не попадает в detail
 * judge.verdict / errors.json в открытом виде" and "значение секрета из env_scope фазы
 * отсутствует во всех trusted-файлах прогона (meta.json, audit.jsonl, run.json)". The
 * deterministic check here echoes the target step's own {@code GIT_TOKEN} secret in its stderr
 * (simulating a check script that prints what it found) — the masker must strip it out of the
 * judge verdict before it ever reaches {@code JudgeVerdict.detail} (run.json), the {@code
 * judge.verdict} audit payload (audit.jsonl), and the accumulated_errors block folded into the
 * target step's next prompt (meta.json, covered separately by {@code AbstractAgentRuntimeTest}).
 */
class PipelineEngineSecretMaskingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TokenBudget BUDGET = new TokenBudget(1_000, Duration.ofMinutes(5), 10);
    private static final String SECRET_VALUE = "s3cr3t-git-token-value";

    @Test
    void secretPrintedByTheCheckScriptIsMaskedInJudgeDetailAccumulatedErrorsAndAudit(@TempDir Path repo)
            throws IOException {
        Path promptDir = repo.resolve("prompts");
        Files.createDirectories(promptDir);
        Files.writeString(promptDir.resolve("work.md"), "do the work");

        AgentStep work = new AgentStep("work", List.of(), "claude", Path.of("prompts/work.md"),
                List.of(), List.of(), List.of("GIT_TOKEN"), RetryPolicy.DEFAULT, BUDGET);
        ScriptStep check = new ScriptStep("review.check", List.of(), List.of("check-target"), Duration.ofSeconds(5));
        JudgeStep review = new JudgeStep("review", List.of("work"), "work",
                Optional.empty(), Optional.of(check), FailPolicy.DEFAULT);
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(work, review));

        SecretStorePort secretStore = envScope -> envScope.contains("GIT_TOKEN")
                ? Map.of("GIT_TOKEN", SECRET_VALUE)
                : Map.of();

        AtomicInteger checkCalls = new AtomicInteger();
        FixtureScriptRunnerPort scriptRunner = new FixtureScriptRunnerPort(inv -> {
            int n = checkCalls.incrementAndGet();
            return n < 2
                    ? new ScriptResult(1, "", "push rejected using token " + SECRET_VALUE + " (401)")
                    : new ScriptResult(0, "ok", "");
        });

        List<String> promptsSeenByWork = new CopyOnWriteArrayList<>();
        FixtureAgentRuntimePort agentRuntime = new FixtureAgentRuntimePort(invocation -> {
            promptsSeenByWork.add(invocation.prompt());
            ObjectNode json = MAPPER.createObjectNode();
            return new AgentResult(0, Optional.of(json), new TokenUsage(1, 1), Path.of("raw.log"));
        });

        InMemoryStateStore stateStore = new InMemoryStateStore();
        try (PipelineEngine engine = new PipelineEngine(stateStore, agentRuntime, scriptRunner,
                ManifestProjectorPort.NOOP, ScopeDiffPort.NOOP, secretStore)) {
            RunId runId = engine.start(TestProjects.minimal(repo), definition, "feature-x");

            until(() -> engine.snapshot(runId).map(s -> s.status() == RunStatus.COMPLETED).orElse(false));

            StepSnapshot reviewSnapshot = engine.snapshot(runId).orElseThrow().steps().stream()
                    .filter(s -> s.stepId().equals("review")).findFirst().orElseThrow();
            assertThat(reviewSnapshot.verdicts()).hasSize(2);
            String failedDetail = reviewSnapshot.verdicts().get(0).detail();
            assertThat(failedDetail).doesNotContain(SECRET_VALUE).contains(SecretMasker.MASK);

            // The masked detail is what got folded into "work"'s retry prompt as
            // accumulated_errors — never the raw secret.
            assertThat(promptsSeenByWork).hasSize(2);
            assertThat(promptsSeenByWork.get(1)).doesNotContain(SECRET_VALUE).contains(SecretMasker.MASK);
        }

        for (AuditEvent event : stateStore.audit()) {
            assertThat(event.payload().toString()).doesNotContain(SECRET_VALUE);
        }
    }
}
