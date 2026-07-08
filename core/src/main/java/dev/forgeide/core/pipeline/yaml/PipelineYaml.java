package dev.forgeide.core.pipeline.yaml;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.validation.InvalidPipelineException;
import dev.forgeide.core.pipeline.validation.PipelineError;
import dev.forgeide.core.pipeline.validation.PipelineValidator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Reads and writes {@code <project>/.forgeide/pipeline.yaml} (SD §5, SDD FR-2.1). Loading maps
 * the YAML onto the domain model and runs the full validator (acyclicity, reachability,
 * references, judge-before-outward, variable scopes); any problem is reported as an
 * {@link InvalidPipelineException} carrying every error with its coordinate.
 */
public final class PipelineYaml {

    private final ObjectMapper mapper;
    private final PipelineValidator validator;

    public PipelineYaml() {
        YAMLFactory factory = YAMLFactory.builder()
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
                .build();
        this.mapper = new ObjectMapper(factory);
        this.validator = new PipelineValidator();
    }

    /** Parses and validates YAML text with no filesystem context. */
    public PipelineDefinition parse(String yaml) {
        return parse(yaml, PipelineValidator.Options.none());
    }

    /** Parses and validates YAML text; {@code options} may add filesystem checks (prompt files). */
    public PipelineDefinition parse(String yaml, PipelineValidator.Options options) {
        JsonNode root;
        try {
            root = mapper.readTree(yaml);
        } catch (IOException e) {
            throw new InvalidPipelineException(List.of(
                    PipelineError.atPipeline("", "malformed YAML: " + e.getMessage())));
        }
        PipelineDefinition definition = new PipelineParser().parse(root);
        List<PipelineError> errors = validator.validate(definition, options);
        if (!errors.isEmpty()) {
            throw new InvalidPipelineException(errors);
        }
        return definition;
    }

    /** Reads, parses and validates a pipeline file. */
    public PipelineDefinition parse(Path file) {
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read pipeline: " + file, e);
        }
    }

    /** Serialises a definition back to YAML (see {@link PipelineWriter} round-trip guarantee). */
    public String serialize(PipelineDefinition definition) {
        return new PipelineWriter(mapper).write(definition);
    }

    /** Serialises {@code definition} and writes it to {@code file} (UTF-8). */
    public void write(PipelineDefinition definition, Path file) {
        try {
            Files.writeString(file, serialize(definition), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write pipeline: " + file, e);
        }
    }
}
