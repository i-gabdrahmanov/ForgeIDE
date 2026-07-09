package dev.forgeide.core.run;

import java.util.Arrays;
import java.util.Optional;

/**
 * The three actions offered by the {@code pending_questions} round-limit escalation dialog
 * (SDD FR-10.5, Т-15), carried as the {@code answer} string of an {@link
 * dev.forgeide.core.event.EngineCommand.GateAnswered} against the escalated step — the same
 * shared dialog infrastructure a {@code GateStep} and a judge escalation (FR-11.3) answer with
 * ({@code EscalationAction}).
 *
 * <p>{@code OPEN_PROMPT} never reaches the engine: T20 (the prompt editor) is out of this task's
 * scope, so the UI renders it as an informational stub link rather than a submittable answer
 * (see {@code GateDialog}). The engine refuses it defensively if it ever arrives anyway.
 */
public enum QuestionEscalationAction {
    OPEN_PROMPT("open_prompt"),
    SPLIT_STEP("split_step"),
    CANCEL("cancel");

    private final String token;

    QuestionEscalationAction(String token) {
        this.token = token;
    }

    public String token() {
        return token;
    }

    public static Optional<QuestionEscalationAction> fromToken(String token) {
        return Arrays.stream(values()).filter(a -> a.token.equals(token)).findFirst();
    }
}
