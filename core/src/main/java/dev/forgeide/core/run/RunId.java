package dev.forgeide.core.run;

import java.util.Objects;
import java.util.UUID;

public record RunId(String value) {

    public RunId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("RunId must not be blank");
        }
    }

    public static RunId newId() {
        return new RunId(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
