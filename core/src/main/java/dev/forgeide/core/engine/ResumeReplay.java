package dev.forgeide.core.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.forgeide.core.audit.AuditEvent;
import dev.forgeide.core.pipeline.PerTaskLoop;
import dev.forgeide.core.pipeline.StepDefinition;
import dev.forgeide.core.project.ProjectDefinition;
import dev.forgeide.core.run.PipelineRun;
import dev.forgeide.core.run.StepStatus;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T28 "replay аудита при resume": reconstructs the parts of a run a live {@link RunContext}
 * accumulates only in memory (per-task-loop expansion, gate/question/judge bookkeeping) purely
 * from what {@link PipelineEngine#rehydrate} already has on hand — the static
 * {@link dev.forgeide.core.pipeline.PipelineDefinition}, the persisted {@link
 * dev.forgeide.core.run.RunSnapshot}, and the audit hash-chain. No ports, no state of its own.
 */
final class ResumeReplay {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ResumeReplay() {
    }

    /** Re-derives the runtime step instances a {@code per_task_loop} already unrolled and passed
     * before the process died — the static {@link dev.forgeide.core.pipeline.PipelineDefinition}
     * alone has no record of them, but re-reading its own {@code task-plan.json} is deterministic
     * (same file the run expanded from originally, modulo FR-3.5 drift, which retry/resume
     * already warns about). */
    static void reExpandIfAlreadyPassed(ProjectDefinition project, PipelineRun run, PerTaskLoop loop,
                                         Map<String, StepDefinition> stepDefs, Map<String, String> templateKeyOf) {
        if (!run.hasStep(loop.id()) || run.step(loop.id()).status() != StepStatus.PASSED) {
            return;
        }
        List<String> taskIds;
        try {
            taskIds = readTaskIds(project.repositoryPath().resolve(loop.taskPlanJson()));
        } catch (IOException ex) {
            throw new UncheckedIOException("resume: failed to re-expand per_task_loop " + loop.id(), ex);
        }
        for (String taskId : taskIds) {
            List<StepDefinition> expanded = TemplateExpansion.expandForTask(loop, taskId);
            for (int i = 0; i < expanded.size(); i++) {
                StepDefinition instance = expanded.get(i);
                stepDefs.put(instance.id(), instance);
                templateKeyOf.put(instance.id(), loop.id() + "/" + loop.template().get(i).id());
            }
        }
    }

    /** Reconstructs the in-memory-only bookkeeping {@link RunContext} normally accumulates live
     * (gate answers, question answers, judge-accumulated errors) by replaying the persisted audit
     * hash-chain — the one part of a run's history {@link dev.forgeide.core.run.RunSnapshot}
     * itself does not carry. */
    static void replayContext(RunContext ctx, List<AuditEvent> auditEvents) {
        for (AuditEvent event : auditEvents) {
            String stepId = event.stepId();
            switch (event.type()) {
                case "gate.answered" -> {
                    JsonNode answer = event.payload().get("answer");
                    if (stepId != null && answer != null && answer.isTextual()) {
                        ctx.gateAnswers.put(stepId, answer.asText());
                    }
                }
                case "question.answered" -> {
                    JsonNode answers = event.payload().get("answers");
                    if (stepId != null && answers != null && answers.isObject()) {
                        Map<String, String> answerMap = new LinkedHashMap<>();
                        answers.fields().forEachRemaining(e -> answerMap.put(e.getKey(), e.getValue().asText()));
                        ctx.lastAnswers.put(stepId, answerMap);
                    }
                }
                case "outward.result" -> {
                    JsonNode branch = event.payload().get("branch");
                    if (stepId != null && branch != null && branch.isTextual()) {
                        ctx.outwardBranches.put(stepId, branch.asText());
                    }
                }
                case "judge.verdict" -> {
                    JsonNode passed = event.payload().get("passed");
                    JsonNode target = event.payload().get("targetStepId");
                    if (target == null) {
                        continue;
                    }
                    if (passed != null && passed.asBoolean(false)) {
                        ctx.accumulatedErrors.remove(target.asText());
                    } else {
                        JsonNode detail = event.payload().get("detail");
                        ctx.accumulatedErrors.computeIfAbsent(target.asText(), k -> new ArrayList<>())
                                .add(detail == null ? "" : detail.asText());
                    }
                }
                default -> { }
            }
        }
    }

    /** Shared with {@link PhaseDispatcher#dispatchPerTaskLoop} — the exact same {@code
     * task-plan.json} parsing, whether unrolling a loop live or re-deriving it on resume. */
    static List<String> readTaskIds(Path taskPlanFile) throws IOException {
        JsonNode root = MAPPER.readTree(taskPlanFile.toFile());
        List<String> ids = new ArrayList<>();
        if (root != null && root.isArray()) {
            for (JsonNode node : root) {
                if (node.isObject() && node.hasNonNull("id")) {
                    ids.add(node.get("id").asText());
                } else if (node.isTextual()) {
                    ids.add(node.asText());
                }
            }
        }
        return ids;
    }
}
