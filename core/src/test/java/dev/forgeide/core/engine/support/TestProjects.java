package dev.forgeide.core.engine.support;

import dev.forgeide.core.project.CriticalityProfile;
import dev.forgeide.core.project.ProjectDefinition;
import dev.forgeide.core.project.ProjectId;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class TestProjects {

    private TestProjects() {
    }

    public static ProjectDefinition minimal(Path repositoryPath) {
        return new ProjectDefinition(ProjectId.newId(), "test-project", repositoryPath,
                List.of(), Map.of(), CriticalityProfile.LOW, List.of());
    }
}
