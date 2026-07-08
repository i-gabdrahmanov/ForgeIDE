package dev.forgeide.core.pipeline.yaml;

import dev.forgeide.core.pipeline.PipelineDefinition;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Bundled {@code pipeline.yaml} templates shipped in IDE resources (SD §8, FR-9.1). For M1
 * only {@code forgelite} exists; {@code feature-pipeline} follows post-MVP. The raw text is
 * also exposed so editors can seed a new project's file with comments intact.
 */
public final class PipelineTemplates {

    private static final String BASE = "/dev/forgeide/core/pipeline/templates/";

    private PipelineTemplates() {
    }

    /** The forgelite template parsed and validated. */
    public static PipelineDefinition forgelite() {
        return new PipelineYaml().parse(forgeliteYaml());
    }

    /** Raw forgelite template text (comments preserved). */
    public static String forgeliteYaml() {
        return read("forgelite.yaml");
    }

    private static String read(String name) {
        try (InputStream in = PipelineTemplates.class.getResourceAsStream(BASE + name)) {
            if (in == null) {
                throw new IllegalStateException("missing bundled template: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read template: " + name, e);
        }
    }
}
