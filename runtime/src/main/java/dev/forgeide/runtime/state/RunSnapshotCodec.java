package dev.forgeide.runtime.state;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.forgeide.core.run.AuditRef;
import dev.forgeide.core.run.FailureReason;
import dev.forgeide.core.run.JudgeVerdict;
import dev.forgeide.core.run.PendingQuestion;
import dev.forgeide.core.run.QuestionType;
import dev.forgeide.core.run.RunHaltReason;
import dev.forgeide.core.run.RunId;
import dev.forgeide.core.run.RunSnapshot;
import dev.forgeide.core.run.RunStatus;
import dev.forgeide.core.run.StepSnapshot;
import dev.forgeide.core.run.StepStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Maps {@link RunSnapshot} onto the JSON tree persisted inside {@code run.json}'s
 * {@code snapshot} field (T07 scope; SD §4, SDD FR-7.1). Field-by-field like
 * {@code ProjectJsonCodec} (T04), so the file stays hand-inspectable.
 */
final class RunSnapshotCodec {

    private final ObjectMapper mapper;

    RunSnapshotCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    ObjectNode toNode(RunSnapshot snapshot) {
        ObjectNode node = mapper.createObjectNode();
        node.put("runId", snapshot.runId().value());
        node.put("featureSlug", snapshot.featureSlug());
        node.put("status", snapshot.status().name());
        putNullableEnum(node, "haltReason", snapshot.haltReason());
        ArrayNode steps = node.putArray("steps");
        snapshot.steps().forEach(s -> steps.add(stepNode(s)));
        return node;
    }

    private ObjectNode stepNode(StepSnapshot step) {
        ObjectNode node = mapper.createObjectNode();
        node.put("stepId", step.stepId());
        node.put("status", step.status().name());
        node.put("iteration", step.iteration());
        putNullableEnum(node, "failureReason", step.failureReason());
        ArrayNode questions = node.putArray("pendingQuestions");
        step.pendingQuestions().forEach(q -> questions.add(questionNode(q)));
        ArrayNode verdicts = node.putArray("verdicts");
        step.verdicts().forEach(v -> verdicts.add(verdictNode(v)));
        ArrayNode events = node.putArray("events");
        step.events().forEach(e -> events.add(auditRefNode(e)));
        return node;
    }

    private ObjectNode questionNode(PendingQuestion q) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", q.id());
        node.put("text", q.text());
        node.put("type", q.type().name());
        ArrayNode options = node.putArray("options");
        q.options().forEach(options::add);
        if (q.context().isPresent()) {
            node.put("context", q.context().get());
        } else {
            node.putNull("context");
        }
        return node;
    }

    private ObjectNode verdictNode(JudgeVerdict v) {
        ObjectNode node = mapper.createObjectNode();
        node.put("iteration", v.iteration());
        if (v.llmPassed().isPresent()) {
            node.put("llmPassed", v.llmPassed().get());
        } else {
            node.putNull("llmPassed");
        }
        node.put("deterministicPassed", v.deterministicPassed());
        node.put("detail", v.detail());
        return node;
    }

    private ObjectNode auditRefNode(AuditRef ref) {
        ObjectNode node = mapper.createObjectNode();
        node.put("seq", ref.seq());
        node.put("type", ref.type());
        return node;
    }

    private void putNullableEnum(ObjectNode node, String field, Optional<? extends Enum<?>> value) {
        if (value.isPresent()) {
            node.put(field, value.get().name());
        } else {
            node.putNull(field);
        }
    }

    RunSnapshot fromNode(JsonNode node) {
        RunId runId = new RunId(text(node, "runId"));
        String featureSlug = text(node, "featureSlug");
        RunStatus status = RunStatus.valueOf(text(node, "status"));
        Optional<RunHaltReason> haltReason = optionalEnum(node, "haltReason", RunHaltReason::valueOf);
        List<StepSnapshot> steps = new ArrayList<>();
        node.path("steps").forEach(n -> steps.add(stepFromNode(n)));
        return new RunSnapshot(runId, featureSlug, status, haltReason, steps);
    }

    private StepSnapshot stepFromNode(JsonNode node) {
        String stepId = text(node, "stepId");
        StepStatus status = StepStatus.valueOf(text(node, "status"));
        int iteration = node.get("iteration").asInt();
        Optional<FailureReason> failureReason = optionalEnum(node, "failureReason", FailureReason::valueOf);
        List<PendingQuestion> questions = new ArrayList<>();
        node.path("pendingQuestions").forEach(n -> questions.add(questionFromNode(n)));
        List<JudgeVerdict> verdicts = new ArrayList<>();
        node.path("verdicts").forEach(n -> verdicts.add(verdictFromNode(n)));
        List<AuditRef> events = new ArrayList<>();
        node.path("events").forEach(n -> events.add(new AuditRef(n.get("seq").asLong(), text(n, "type"))));
        return new StepSnapshot(stepId, status, iteration, failureReason, questions, verdicts, events);
    }

    private PendingQuestion questionFromNode(JsonNode node) {
        String id = text(node, "id");
        String questionText = text(node, "text");
        QuestionType type = QuestionType.valueOf(text(node, "type"));
        List<String> options = new ArrayList<>();
        node.path("options").forEach(n -> options.add(n.asText()));
        Optional<String> context = optionalText(node, "context");
        return new PendingQuestion(id, questionText, type, options, context);
    }

    private JudgeVerdict verdictFromNode(JsonNode node) {
        int iteration = node.get("iteration").asInt();
        JsonNode llm = node.get("llmPassed");
        Optional<Boolean> llmPassed = (llm == null || llm.isNull()) ? Optional.empty() : Optional.of(llm.asBoolean());
        boolean deterministicPassed = node.get("deterministicPassed").asBoolean();
        String detail = text(node, "detail");
        return new JudgeVerdict(iteration, llmPassed, deterministicPassed, detail);
    }

    private Optional<String> optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? Optional.empty() : Optional.of(value.asText());
    }

    private <E extends Enum<E>> Optional<E> optionalEnum(JsonNode node, String field, Function<String, E> parse) {
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? Optional.empty() : Optional.of(parse.apply(value.asText()));
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalStateException("run.json: missing or non-string field '" + field + "' in " + node);
        }
        return value.asText();
    }
}
