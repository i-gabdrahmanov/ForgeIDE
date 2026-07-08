package dev.forgeide.core.pipeline;

import java.util.Objects;
import java.util.Optional;

/**
 * A project-level parameter declared in {@code pipeline.yaml → params} (SD §5, FR-1.1).
 * The IDE renders a form field per param; the filled values feed the {@code ${params.*}}
 * substitutions at run time.
 *
 * @param hint optional help text shown next to the form field
 */
public record PipelineParam(String name, boolean required, Optional<String> hint) {

    public PipelineParam {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(hint, "hint");
    }

    public static PipelineParam required(String name) {
        return new PipelineParam(name, true, Optional.empty());
    }
}
