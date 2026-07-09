package dev.forgeide.core.engine;

import dev.forgeide.core.audit.AuditEvent;
import dev.forgeide.core.engine.support.FixtureAgentRuntimePort;
import dev.forgeide.core.engine.support.FixtureHarnessGuardPort;
import dev.forgeide.core.engine.support.FixtureScriptRunnerPort;
import dev.forgeide.core.engine.support.InMemoryStateStore;
import dev.forgeide.core.engine.support.TestProjects;
import dev.forgeide.core.pipeline.JudgeStep;
import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.ScriptStep;
import dev.forgeide.core.policy.FailPolicy;
import dev.forgeide.core.port.ManifestProjectorPort;
import dev.forgeide.core.port.OutwardActionsPort;
import dev.forgeide.core.port.ScopeDiffPort;
import dev.forgeide.core.port.SecretStorePort;
import dev.forgeide.core.run.RunId;
import dev.forgeide.core.run.RunStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static dev.forgeide.core.engine.support.Await.until;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * T18 acceptance: "судья исполняется из harness-cache (путь зафиксирован в аудите)" (SR-7's
 * prevention half of Т-7) — a judge's deterministic-check script path under the project's harness
 * is resolved to the IDE's cache copy before it ever runs, and that resolved path is recorded in
 * the audit trail regardless of the check's own verdict.
 */
class PipelineEngineHarnessCacheTest {

    @Test
    void judgeDeterministicCheckRunsTheCacheResolvedCommandAndItIsAudited(@TempDir Path repo) {
        ScriptStep work = new ScriptStep("work", List.of(), List.of("run-target"), Duration.ofSeconds(5));
        ScriptStep check = new ScriptStep("review.check", List.of(),
                List.of("python3", ".gigacode/hooks/check_coverage.py"), Duration.ofSeconds(5));
        JudgeStep review = new JudgeStep("review", List.of("work"), "work",
                Optional.empty(), Optional.of(check), FailPolicy.DEFAULT);
        PipelineDefinition definition = new PipelineDefinition("p", 1, List.of(work, review));

        FixtureHarnessGuardPort harnessGuard = new FixtureHarnessGuardPort();
        String cachedScript = "/forgeide-cache/current-hash/hooks/check_coverage.py";
        harnessGuard.cacheResolver = command -> command.stream()
                .map(token -> token.equals(".gigacode/hooks/check_coverage.py") ? cachedScript : token)
                .toList();

        List<List<String>> checkInvocations = new CopyOnWriteArrayList<>();
        FixtureScriptRunnerPort scriptRunner = new FixtureScriptRunnerPort(inv -> {
            if (inv.command().equals(List.of("run-target"))) {
                return new dev.forgeide.core.port.ScriptResult(0, "ok", "");
            }
            checkInvocations.add(inv.command());
            return new dev.forgeide.core.port.ScriptResult(0, "coverage ok", "");
        });

        InMemoryStateStore stateStore = new InMemoryStateStore();
        FixtureAgentRuntimePort agentRuntime = new FixtureAgentRuntimePort(inv -> {
            throw new AssertionError("no agent step in this pipeline");
        });

        try (PipelineEngine engine = new PipelineEngine(stateStore, agentRuntime, scriptRunner,
                ManifestProjectorPort.NOOP, ScopeDiffPort.NOOP, SecretStorePort.NOOP, OutwardActionsPort.NOOP,
                harnessGuard)) {
            RunId runId = engine.start(TestProjects.minimal(repo), definition, "feature-x");

            until(() -> engine.snapshot(runId).map(s -> s.status() == RunStatus.COMPLETED).orElse(false));

            assertThat(harnessGuard.resolvedCommands).hasSize(1);
            assertThat(checkInvocations).hasSize(1);
            assertThat(checkInvocations.get(0)).containsExactly("python3", cachedScript);

            AuditEvent resolved = stateStore.loadAudit(runId).stream()
                    .filter(e -> e.type().equals("judge.script.resolved")).findFirst().orElseThrow();
            List<String> auditedCommand = new java.util.ArrayList<>();
            resolved.payload().get("command").forEach(n -> auditedCommand.add(n.asText()));
            assertThat(auditedCommand).containsExactly("python3", cachedScript);
        }
    }
}
