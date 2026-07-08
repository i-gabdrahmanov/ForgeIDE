package dev.forgeide.core.engine;

import com.fasterxml.jackson.databind.JsonNode;
import dev.forgeide.core.run.PendingQuestion;
import dev.forgeide.core.run.QuestionType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Tolerant reader for the {@code pending_questions} array of the phase result contract
 * (SDD §5.2, FR-10.1). Malformed entries are dropped rather than failing the whole phase —
 * the real contract enforcement belongs to T09/T13.
 */
final class PendingQuestions {

    private PendingQuestions() {
    }

    static List<PendingQuestion> parse(JsonNode finalJson) {
        List<PendingQuestion> out = new ArrayList<>();
        JsonNode arr = finalJson.get("pending_questions");
        if (arr == null || !arr.isArray()) {
            return out;
        }
        for (JsonNode q : arr) {
            String id = q.path("id").asText("");
            String text = q.path("text").asText("");
            if (id.isBlank() || text.isBlank()) {
                continue;
            }
            QuestionType type = "choice".equalsIgnoreCase(q.path("type").asText("text"))
                    ? QuestionType.CHOICE : QuestionType.TEXT;
            List<String> options = new ArrayList<>();
            JsonNode optionsNode = q.get("options");
            if (optionsNode != null && optionsNode.isArray()) {
                optionsNode.forEach(o -> options.add(o.asText()));
            }
            Optional<String> context = q.hasNonNull("context")
                    ? Optional.of(q.get("context").asText())
                    : Optional.empty();
            try {
                out.add(new PendingQuestion(id, text, type, options, context));
            } catch (IllegalArgumentException malformed) {
                // e.g. a "choice" question with no options — drop it rather than fail the phase.
            }
        }
        return out;
    }
}
