package dev.forgeide.core.port;

import java.util.Map;

public record QuestionAnswers(Map<String, String> answers) {

    public QuestionAnswers {
        answers = Map.copyOf(answers);
    }
}
