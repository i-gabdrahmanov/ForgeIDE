package dev.forgeide.core.port;

import java.util.Objects;

public record ScriptResult(int exitCode, String stdout, String stderr) {

    public ScriptResult {
        Objects.requireNonNull(stdout, "stdout");
        Objects.requireNonNull(stderr, "stderr");
    }
}
