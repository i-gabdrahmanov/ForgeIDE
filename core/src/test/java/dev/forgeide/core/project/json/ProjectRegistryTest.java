package dev.forgeide.core.project.json;

import dev.forgeide.core.project.CriticalityProfile;
import dev.forgeide.core.project.JiraProjectConfig;
import dev.forgeide.core.project.OutwardConfig;
import dev.forgeide.core.project.PrProvider;
import dev.forgeide.core.project.PrRepoConfig;
import dev.forgeide.core.project.ProjectDefinition;
import dev.forgeide.core.project.ProjectId;
import dev.forgeide.core.project.RuntimeBinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectRegistryTest {

    private static ProjectDefinition sample(ProjectId id, String name) {
        RuntimeBinding claude = new RuntimeBinding("claude", Path.of("/usr/local/bin/claude"),
                List.of("--experimental-hooks"));
        return new ProjectDefinition(id, name, Path.of("/repo/" + name),
                List.of(Path.of("specs/sdd.md")), Map.of("jira_key", "FORGE-1"),
                CriticalityProfile.MEDIUM, List.of(claude));
    }

    @Test
    void emptyRegistryHasNoProjects(@TempDir Path dir) {
        ProjectRegistry registry = new ProjectRegistry(dir.resolve("projects.json"));

        assertThat(registry.list()).isEmpty();
        assertThat(registry.find(ProjectId.newId())).isEmpty();
    }

    @Test
    void persistsAndReloadsAfterRestart(@TempDir Path dir) {
        Path file = dir.resolve("projects.json");
        ProjectDefinition project = sample(ProjectId.newId(), "alpha");
        new ProjectRegistry(file).save(project);

        // A fresh instance simulates an IDE restart.
        ProjectRegistry reopened = new ProjectRegistry(file);

        assertThat(reopened.list()).containsExactly(project);
        assertThat(reopened.find(project.id())).contains(project);
    }

    @Test
    void savingTwiceUpsertsRatherThanDuplicating(@TempDir Path dir) {
        ProjectRegistry registry = new ProjectRegistry(dir.resolve("projects.json"));
        ProjectId id = ProjectId.newId();
        registry.save(sample(id, "alpha"));

        ProjectDefinition renamed = sample(id, "alpha-renamed");
        registry.save(renamed);

        assertThat(registry.list()).containsExactly(renamed);
    }

    @Test
    void keepsMultipleProjectsIndependent(@TempDir Path dir) {
        ProjectRegistry registry = new ProjectRegistry(dir.resolve("projects.json"));
        ProjectDefinition a = sample(ProjectId.newId(), "alpha");
        ProjectDefinition b = sample(ProjectId.newId(), "beta");
        registry.save(a);
        registry.save(b);

        assertThat(registry.list()).containsExactlyInAnyOrder(a, b);
    }

    @Test
    void persistsAndReloadsOutwardConfig(@TempDir Path dir) {
        Path file = dir.resolve("projects.json");
        OutwardConfig outward = new OutwardConfig("upstream", "release",
                Optional.of(new PrRepoConfig(PrProvider.BITBUCKET, "https://api.bitbucket.org/2.0", "acme/demo")),
                Optional.of(new JiraProjectConfig("https://acme.atlassian.net", "Done")));
        ProjectDefinition project = new ProjectDefinition(ProjectId.newId(), "alpha", Path.of("/repo/alpha"),
                List.of(), Map.of(), CriticalityProfile.LOW, List.of(), outward);
        new ProjectRegistry(file).save(project);

        ProjectRegistry reopened = new ProjectRegistry(file);

        assertThat(reopened.find(project.id())).contains(project);
        assertThat(reopened.find(project.id()).orElseThrow().outward()).isEqualTo(outward);
    }

    @Test
    void deleteRemovesProject(@TempDir Path dir) {
        ProjectRegistry registry = new ProjectRegistry(dir.resolve("projects.json"));
        ProjectDefinition project = sample(ProjectId.newId(), "alpha");
        registry.save(project);

        registry.delete(project.id());

        assertThat(registry.list()).isEmpty();
    }
}
