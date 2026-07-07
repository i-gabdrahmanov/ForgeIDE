package dev.forgeide.core.port;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ScriptInvocation(Path workingDir, List<String> command, Duration timeout, Map<String, String> env) {

    public ScriptInvocation {
        Objects.requireNonNull(workingDir, "workingDir");
        Objects.requireNonNull(timeout, "timeout");
        command = List.copyOf(command);
        env = Map.copyOf(env);
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
    }
}
