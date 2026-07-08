package dev.forgeide.core.project;

import java.util.Objects;
import java.util.UUID;

public record ProjectId(String value) {

    public ProjectId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("ProjectId must not be blank");
        }
    }

    public static ProjectId newId() {
        return new ProjectId(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
