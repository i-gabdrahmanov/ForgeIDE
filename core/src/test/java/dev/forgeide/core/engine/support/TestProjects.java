package dev.forgeide.core.engine.support;

import dev.forgeide.core.project.CriticalityProfile;
import dev.forgeide.core.project.ProjectDefinition;
import dev.forgeide.core.project.ProjectId;
import dev.forgeide.core.project.RuntimeBinding;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class TestProjects {

    private TestProjects() {
    }

    /** Registers placeholder {@code claude}/{@code gigacode} bindings so steps using either
     * {@code runtimeRef} resolve; fixture {@code AgentRuntimePort}s never read the binary path. */
    public static ProjectDefinition minimal(Path repositoryPath) {
        return new ProjectDefinition(ProjectId.newId(), "test-project", repositoryPath,
                List.of(), Map.of(), CriticalityProfile.LOW,
                List.of(new RuntimeBinding("claude", Path.of("/usr/bin/claude")),
                        new RuntimeBinding("gigacode", Path.of("/usr/bin/gigacode"))));
    }
}
