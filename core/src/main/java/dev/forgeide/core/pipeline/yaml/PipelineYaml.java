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
import java.util.Objects;
import java.util.Optional;

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
        ParseResult result = parseLenient(yaml, options);
        if (!result.errors().isEmpty()) {
            throw new InvalidPipelineException(result.errors());
        }
        return result.definition().orElseThrow();
    }

    /** Reads, parses and validates a pipeline file. */
    public PipelineDefinition parse(Path file) {
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read pipeline: " + file, e);
        }
    }

    /**
     * Non-throwing variant for the canvas (T05, FR-2.3): field-level structural problems
     * (missing required field, unparsable YAML) leave no model to show and are reported with an
     * empty {@link ParseResult#definition()}. Everything else — cycles, dangling references,
     * unreachable steps, missing judge-before-outward, unknown variable scopes — is caught by
     * {@link PipelineValidator} only once a complete {@link PipelineDefinition} already exists,
     * so that model is returned alongside the errors: the canvas can render every tile and badge
     * the offending ones instead of showing nothing.
     */
    public ParseResult parseLenient(String yaml) {
        return parseLenient(yaml, PipelineValidator.Options.none());
    }

    /** {@link #parseLenient(String)} with filesystem-aware checks (prompt files). */
    public ParseResult parseLenient(String yaml, PipelineValidator.Options options) {
        JsonNode root;
        try {
            root = mapper.readTree(yaml);
        } catch (IOException e) {
            return new ParseResult(Optional.empty(), List.of(
                    PipelineError.atPipeline("", "malformed YAML: " + e.getMessage())));
        }
        PipelineDefinition definition;
        try {
            definition = new PipelineParser().parse(root);
        } catch (InvalidPipelineException e) {
            return new ParseResult(Optional.empty(), e.errors());
        }
        List<PipelineError> errors = validator.validate(definition, options);
        return new ParseResult(Optional.of(definition), errors);
    }

    /** {@link #parseLenient(String)} reading the YAML from {@code file} first. */
    public ParseResult parseLenient(Path file) {
        try {
            return parseLenient(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read pipeline: " + file, e);
        }
    }

    /**
     * @param definition best-effort model; empty only when no model could be built at all
     * @param errors     every validation problem found, each carrying its coordinate (T05 badges)
     */
    public record ParseResult(Optional<PipelineDefinition> definition, List<PipelineError> errors) {

        public ParseResult {
            Objects.requireNonNull(definition, "definition");
            errors = List.copyOf(errors);
        }

        public boolean isValid() {
            return errors.isEmpty();
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
