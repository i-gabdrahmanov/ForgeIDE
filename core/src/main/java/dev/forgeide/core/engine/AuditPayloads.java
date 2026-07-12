package dev.forgeide.core.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.forgeide.core.event.EngineCommand;
import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.port.HarnessGuardPort;
import dev.forgeide.core.run.FailureReason;
import dev.forgeide.core.run.PendingQuestion;

import java.util.List;

/**
 * T28: every {@code ObjectNode} audit/event payload {@link PipelineEngine} and its collaborators
 * build, in one place — pure functions, no port/state dependencies, so moving them here changes
 * nothing about what ends up on disk (audit.jsonl payloads, run.json via {@code StepRun}), just
 * where the code that builds them lives.
 */
final class AuditPayloads {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AuditPayloads() {
    }

    /** Shared empty payload for audit events that carry no data of their own. */
    static ObjectNode empty() {
        return MAPPER.createObjectNode();
    }

    static ObjectNode runStartedPayload(String featureSlug, PipelineDefinition definition, List<String> stepIds) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("featureSlug", featureSlug);
        payload.put("pipelineId", definition.id());
        ArrayNode ids = payload.putArray("stepIds");
        stepIds.forEach(ids::add);
        return payload;
    }

    static ObjectNode haltPayload(String reason, String detail) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("reason", reason);
        payload.put("detail", detail);
        return payload;
    }

    static ObjectNode questionAskedPayload(List<PendingQuestion> questions) {
        ObjectNode payload = MAPPER.createObjectNode();
        ArrayNode array = payload.putArray("questions");
        for (PendingQuestion q : questions) {
            ObjectNode qNode = array.addObject();
            qNode.put("id", q.id());
            qNode.put("text", q.text());
            qNode.put("type", q.type().name());
            if (!q.options().isEmpty()) {
                ArrayNode options = qNode.putArray("options");
                q.options().forEach(options::add);
            }
            q.context().ifPresent(c -> qNode.put("context", c));
        }
        return payload;
    }

    static ObjectNode failedPayload(FailureReason reason, String detail) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("reason", reason.name());
        payload.put("detail", detail);
        return payload;
    }

    static ObjectNode retriedPayload(boolean auto) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("auto", auto);
        return payload;
    }

    static ObjectNode autoRetriedPayload(int attempt, int max) {
        ObjectNode payload = retriedPayload(true);
        payload.put("attempt", attempt);
        payload.put("max", max);
        return payload;
    }

    static ObjectNode verdictPayload(String targetStepId, boolean passed, String detail) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("targetStepId", targetStepId);
        payload.put("passed", passed);
        payload.put("detail", detail);
        return payload;
    }

    static ObjectNode gateRequestedPayload(String question, List<String> options, String targetStepId) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("question", question);
        ArrayNode array = payload.putArray("options");
        options.forEach(array::add);
        payload.put("targetStepId", targetStepId);
        return payload;
    }

    static ObjectNode gateAnsweredPayload(EngineCommand.GateAnswered cmd) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("answer", cmd.answer());
        payload.put("user", cmd.user());
        payload.put("at", cmd.at().toString());
        cmd.detail().ifPresent(d -> payload.put("detail", d));
        payload.put("diffAcked", cmd.diffAcked());
        return payload;
    }

    static ObjectNode overridePayload(String judgeStepId, String reason) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("judgeStepId", judgeStepId);
        payload.put("reason", reason);
        return payload;
    }

    static ObjectNode questionAnsweredPayload(EngineCommand.QuestionsAnswered cmd) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("user", cmd.user());
        payload.put("at", cmd.at().toString());
        ObjectNode answers = payload.putObject("answers");
        cmd.answers().forEach(answers::put);
        return payload;
    }

    static ObjectNode orphanSweptPayload(List<Long> pids) {
        ObjectNode payload = MAPPER.createObjectNode();
        ArrayNode array = payload.putArray("pids");
        pids.forEach(array::add);
        return payload;
    }

    /** T36/SR-6: paths already dirty in {@code git status} at run start — scope-diff exempts
     * exactly these from every phase's {@code allowed_write} check for the run's whole duration
     * (see {@code GitScopeDiff}'s class doc), so the audit trail names them up front. */
    static ObjectNode dirtyTreePayload(List<String> paths) {
        ObjectNode payload = MAPPER.createObjectNode();
        ArrayNode array = payload.putArray("paths");
        paths.forEach(array::add);
        return payload;
    }

    static ObjectNode promptDriftPayload(String path, String snapshot, String current) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("promptPath", path);
        payload.put("diff", PromptDiff.summarize(snapshot, current));
        return payload;
    }

    static ObjectNode promptEditedPayload(String path, String previous, EngineCommand.PromptEdited cmd) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("promptPath", path);
        payload.put("user", cmd.user());
        payload.put("at", cmd.at().toString());
        payload.put("diffHash", sha256Hex(cmd.content()));
        payload.put("diff", PromptDiff.summarize(previous, cmd.content()));
        return payload;
    }

    private static String sha256Hex(String text) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static ObjectNode harnessEditedPayload(HarnessGuardPort.HarnessEditResult result,
                                            EngineCommand.HarnessEdited cmd) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("relativePath", cmd.relativePath());
        payload.put("oldHash", result.oldHash());
        payload.put("newHash", result.newHash());
        payload.put("diff", result.diff());
        payload.put("user", cmd.user());
        payload.put("at", cmd.at().toString());
        return payload;
    }

    static ObjectNode harnessDriftResolvedPayload(EngineCommand.HarnessDriftResolved cmd) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("action", cmd.action().token());
        payload.put("user", cmd.user());
        payload.put("at", cmd.at().toString());
        return payload;
    }

    static ObjectNode judgeScriptResolvedPayload(List<String> command) {
        ObjectNode payload = MAPPER.createObjectNode();
        ArrayNode cmd = payload.putArray("command");
        command.forEach(cmd::add);
        return payload;
    }

    static ObjectNode judgeDryRunPayload(String judgeStepId, boolean passed, String detail) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("judgeStepId", judgeStepId);
        payload.put("passed", passed);
        payload.put("detail", detail);
        return payload;
    }

    static ObjectNode outwardResultPayload(EngineCommand.OutwardCompleted cmd) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("branch", cmd.branch());
        ObjectNode refs = payload.putObject("resultRefs");
        cmd.resultRefs().forEach(refs::put);
        return payload;
    }
}
