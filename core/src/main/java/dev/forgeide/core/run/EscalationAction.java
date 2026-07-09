package dev.forgeide.core.run;

import java.util.Arrays;
import java.util.Optional;

/**
 * The five actions offered by the judge fail-policy-exhaustion escalation dialog (SDD FR-11.3),
 * carried as the {@code answer} string of an {@link dev.forgeide.core.event.EngineCommand.GateAnswered}
 * against the escalated {@code JudgeStep} — the same command type a real {@code GateStep}
 * answers with, per FR-11.3's "shared infrastructure with gates".
 */
public enum EscalationAction {
    RETRY("retry"),
    EDIT_PROMPT("edit_prompt"),
    RESET_CHAIN("reset_chain"),
    CANCEL("cancel"),
    OVERRIDE("override");

    private final String token;

    EscalationAction(String token) {
        this.token = token;
    }

    public String token() {
        return token;
    }

    public static Optional<EscalationAction> fromToken(String token) {
        return Arrays.stream(values()).filter(a -> a.token.equals(token)).findFirst();
    }
}
