package dev.forgeide.importer;

import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.importer.registry.SkillsRegistryEntry;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Everything one completed {@link ImportSession} produces (SD §8): the template pipeline
 * unchanged in shape (paths were already fixed by the bundled template — importing only supplies
 * their content), every file that needs writing to the target project keyed by its project-root
 * relative path, the parsed tile registry, and the step→registry-id mapping the canvas needs to
 * badge validity (T05's {@code TileValidityChecker}, wired up via {@code RegistryTileValidityChecker}).
 */
public record ImportResult(
        PipelineDefinition pipeline,
        Map<Path, String> files,
        List<SkillsRegistryEntry> registry,
        Map<String, String> stepToRegistryId
) {

    public ImportResult {
        Objects.requireNonNull(pipeline, "pipeline");
        files = Map.copyOf(files);
        registry = List.copyOf(registry);
        stepToRegistryId = Map.copyOf(stepToRegistryId);
    }
}
