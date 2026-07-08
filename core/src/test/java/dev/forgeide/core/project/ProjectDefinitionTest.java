package dev.forgeide.core.project;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectDefinitionTest {

    private static ProjectDefinition project(List<RuntimeBinding> runtimes) {
        return new ProjectDefinition(ProjectId.newId(), "demo", Path.of("/repo"),
                List.of(), Map.of(), CriticalityProfile.MEDIUM, runtimes);
    }

    @Test
    void rejectsBlankName() {
        assertThatThrownBy(() -> new ProjectDefinition(ProjectId.newId(), " ", Path.of("/repo"),
                List.of(), Map.of(), CriticalityProfile.LOW, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDuplicateRuntimeNames() {
        RuntimeBinding a = new RuntimeBinding("claude", Path.of("/bin/claude"));
        RuntimeBinding b = new RuntimeBinding("claude", Path.of("/usr/local/bin/claude"));

        assertThatThrownBy(() -> project(List.of(a, b)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("claude");
    }

    @Test
    void resolvesRuntimeByName() {
        RuntimeBinding claude = new RuntimeBinding("claude", Path.of("/bin/claude"), List.of("--experimental-hooks"));
        ProjectDefinition project = project(List.of(claude));

        assertThat(project.runtime("claude")).contains(claude);
        assertThat(project.runtime("missing")).isEmpty();
    }

    @Test
    void criticalityMapsToRiskCeiling() {
        assertThat(CriticalityProfile.LOW.maxRisk()).isEqualTo(RiskLevel.R2);
        assertThat(CriticalityProfile.MEDIUM.maxRisk()).isEqualTo(RiskLevel.R1);
        assertThat(CriticalityProfile.HIGH.maxRisk()).isEqualTo(RiskLevel.R0);
    }
}
