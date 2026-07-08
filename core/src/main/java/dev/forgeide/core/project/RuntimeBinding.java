package dev.forgeide.core.project;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * A runtime connected to a project (SDD FR-1.2, BT §4.1): {@code name} is what
 * {@code pipeline.yaml} steps reference via {@code runtime} (e.g. {@code claude}, {@code
 * gigacode}), {@code binaryPath} and {@code flags} (e.g. {@code --experimental-hooks}) are
 * used both to probe availability and, later, to invoke it (T06+).
 */
public record RuntimeBinding(String name, Path binaryPath, List<String> flags) {

    public RuntimeBinding {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("RuntimeBinding name must not be blank");
        }
        Objects.requireNonNull(binaryPath, "binaryPath");
        flags = List.copyOf(flags);
    }

    public RuntimeBinding(String name, Path binaryPath) {
        this(name, binaryPath, List.of());
    }
}
