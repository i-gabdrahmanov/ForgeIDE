package dev.forgeide.core.port;

import java.time.Instant;
import java.util.Objects;

public record GateAnswer(String answer, String user, Instant at) {

    public GateAnswer {
        Objects.requireNonNull(answer, "answer");
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(at, "at");
    }
}
