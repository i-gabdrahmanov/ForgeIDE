package dev.forgeide.core.run;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code pending_questions} entry from the phase result contract (SDD §5.2, FR-10.1).
 *
 * @param context path to the artifact/fragment rendered next to the question
 */
public record PendingQuestion(
        String id,
        String text,
        QuestionType type,
        List<String> options,
        Optional<String> context
) {

    public PendingQuestion {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(context, "context");
        options = List.copyOf(options);
        if (type == QuestionType.CHOICE && options.isEmpty()) {
            throw new IllegalArgumentException("choice question requires at least one option");
        }
    }
}
