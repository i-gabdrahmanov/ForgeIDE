package dev.forgeide.runtime.state;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectHashTest {

    @Test
    void sameRepositoryPathAlwaysHashesTheSame(@TempDir Path repo) {
        assertThat(ProjectHash.of(repo)).isEqualTo(ProjectHash.of(repo));
    }

    @Test
    void differentRepositoriesHashDifferently(@TempDir Path repoA, @TempDir Path repoB) {
        assertThat(ProjectHash.of(repoA)).isNotEqualTo(ProjectHash.of(repoB));
    }

    @Test
    void isHexEncodedSha256() {
        String hash = ProjectHash.of(Path.of("/tmp/does-not-need-to-exist"));

        assertThat(hash).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    void equivalentPathsToTheSameRealDirectoryHashTheSame(@TempDir Path repo) throws IOException {
        Path nested = repo.resolve("a").resolve("..");
        Files.createDirectories(repo.resolve("a"));

        assertThat(ProjectHash.of(repo)).isEqualTo(ProjectHash.of(nested));
    }
}
