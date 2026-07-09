package dev.forgeide.core.project;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A project registered with the IDE (SDD FR-1.1-1.3, BT §4.1): the repository it drives, the
 * runtimes it may invoke, the values filled for {@code pipeline.yaml → params} plus any
 * free-form key-value extras, and the criticality profile that caps auto-approved risk.
 *
 * @param specPaths paths (relative to {@code repositoryPath} or absolute) to spec documents
 * @param paramValues values for {@code pipeline.yaml → params} plus arbitrary user-added keys;
 *                    exposed to tiles as {@code ${params.*}}
 */
public record ProjectDefinition(
        ProjectId id,
        String name,
        Path repositoryPath,
        List<Path> specPaths,
        Map<String, String> paramValues,
        CriticalityProfile criticality,
        List<RuntimeBinding> runtimes,
        OutwardConfig outward
) {

    /** Convenience for the common case of no {@code outward:} config (keeps older call sites
     * terse, same pattern as {@code ScriptStep}'s retry-less constructor) — T17's outward steps
     * still run (git_push against {@code origin}/{@code main}), but {@code create_pr}/{@code
     * jira_*} refuse until a real {@link OutwardConfig} is set. */
    public ProjectDefinition(ProjectId id, String name, Path repositoryPath, List<Path> specPaths,
                              Map<String, String> paramValues, CriticalityProfile criticality,
                              List<RuntimeBinding> runtimes) {
        this(id, name, repositoryPath, specPaths, paramValues, criticality, runtimes, OutwardConfig.EMPTY);
    }

    public ProjectDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("project name must not be blank");
        }
        Objects.requireNonNull(repositoryPath, "repositoryPath");
        Objects.requireNonNull(criticality, "criticality");
        specPaths = List.copyOf(specPaths);
        paramValues = Map.copyOf(paramValues);
        runtimes = List.copyOf(runtimes);
        outward = Objects.requireNonNullElse(outward, OutwardConfig.EMPTY);

        Set<String> seen = new HashSet<>();
        for (RuntimeBinding runtime : runtimes) {
            if (!seen.add(runtime.name())) {
                throw new IllegalArgumentException("duplicate runtime name: " + runtime.name());
            }
        }
    }

    public Optional<RuntimeBinding> runtime(String name) {
        return runtimes.stream().filter(r -> r.name().equals(name)).findFirst();
    }
}
