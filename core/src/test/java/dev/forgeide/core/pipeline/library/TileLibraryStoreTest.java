package dev.forgeide.core.pipeline.library;

import dev.forgeide.core.pipeline.AgentStep;
import dev.forgeide.core.pipeline.GateStep;
import dev.forgeide.core.pipeline.JudgeStep;
import dev.forgeide.core.pipeline.ScriptStep;
import dev.forgeide.core.pipeline.StepDefinition;
import dev.forgeide.core.policy.FailPolicy;
import dev.forgeide.core.policy.RetryPolicy;
import dev.forgeide.core.policy.TokenBudget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** T23/FR-2.9: round-trips a saved tile/subgraph through the on-disk library layout. */
class TileLibraryStoreTest {

    private final TileLibraryStore store = new TileLibraryStore();

    private static AgentStep agent(String id, List<String> dependsOn) {
        return new AgentStep(id, dependsOn, "claude", Path.of("prompts", id + ".md"),
                List.of(), List.of(), List.of(), RetryPolicy.DEFAULT, TokenBudget.DEFAULT);
    }

    private static JudgeStep judge(String id, String target, List<String> dependsOn) {
        ScriptStep check = new ScriptStep(id + ".check", List.of(),
                List.of("python3", ".gigacode/skills/forgelite/scripts/check.py"), Duration.ofMinutes(5));
        return new JudgeStep(id, dependsOn, target, Optional.empty(), Optional.of(check), FailPolicy.DEFAULT);
    }

    private static GateStep gate(String id, List<String> dependsOn) {
        return new GateStep(id, dependsOn, "Deliver?", List.of("yes", "no"), List.of());
    }

    private LibraryTileMetadata metadata(String id) {
        return new LibraryTileMetadata(id, "Agent + judge + gate", "camiah",
                Optional.of(LocalDate.of(2026, 12, 31)), List.of("agent", "judge", "gate"), Instant.parse("2026-07-10T10:00:00Z"));
    }

    @Test
    void savedTileIsAbsentUntilSaved(@TempDir Path root) {
        Path libraryDir = LibraryScope.PROJECT.directory(root);
        assertThat(store.list(libraryDir)).isEmpty();
        assertThat(store.read(libraryDir, "agent-judge-gate")).isEmpty();
    }

    @Test
    void roundTripsStepsFilesAndMetadata(@TempDir Path root) {
        Path libraryDir = LibraryScope.PROJECT.directory(root);
        List<StepDefinition> steps = List.of(
                agent("lite-green", List.of()),
                judge("judge-coverage", "lite-green", List.of("lite-green")),
                gate("gate-deliver", List.of("judge-coverage")));
        Map<String, String> files = Map.of(
                "prompts/lite-green.md", "# do the thing",
                ".gigacode/skills/forgelite/scripts/check.py", "print('ok')");
        LibraryTile tile = new LibraryTile(metadata("agent-judge-gate"), steps, files);

        store.save(libraryDir, tile);
        Optional<LibraryTile> reloaded = store.read(libraryDir, "agent-judge-gate");

        assertThat(reloaded).isPresent();
        LibraryTile back = reloaded.get();
        assertThat(back.steps()).extracting(StepDefinition::id)
                .containsExactly("lite-green", "judge-coverage", "gate-deliver");
        assertThat(back.steps().get(1).dependsOn()).containsExactly("lite-green");
        assertThat(back.files()).containsExactlyInAnyOrderEntriesOf(files);
        assertThat(back.metadata().title()).isEqualTo("Agent + judge + gate");
        assertThat(back.metadata().owner()).isEqualTo("camiah");
        assertThat(back.metadata().scope()).containsExactly("agent", "judge", "gate");
        assertThat(back.metadata().validUntil()).contains(LocalDate.of(2026, 12, 31));
        assertThat(back.metadata().savedAt()).isEqualTo(Instant.parse("2026-07-10T10:00:00Z"));
    }

    @Test
    void listReturnsEveryEntrySortedByTitle(@TempDir Path root) {
        Path libraryDir = root.resolve("library");
        store.save(libraryDir, new LibraryTile(
                new LibraryTileMetadata("b-tile", "Bravo", "o", Optional.empty(), List.of(), Instant.now()),
                List.of(agent("a", List.of())), Map.of()));
        store.save(libraryDir, new LibraryTile(
                new LibraryTileMetadata("a-tile", "Alpha", "o", Optional.empty(), List.of(), Instant.now()),
                List.of(agent("a", List.of())), Map.of()));

        assertThat(store.list(libraryDir)).extracting(LibraryTileMetadata::title).containsExactly("Alpha", "Bravo");
    }

    @Test
    void deleteRemovesTheEntry(@TempDir Path root) {
        Path libraryDir = root.resolve("library");
        store.save(libraryDir, new LibraryTile(metadata("solo"), List.of(agent("a", List.of())), Map.of()));
        assertThat(store.read(libraryDir, "solo")).isPresent();

        store.delete(libraryDir, "solo");

        assertThat(store.read(libraryDir, "solo")).isEmpty();
        assertThat(store.list(libraryDir)).isEmpty();
    }

    @Test
    void resavingUnderTheSameIdReplacesFilesRatherThanAccumulatingThem(@TempDir Path root) {
        Path libraryDir = root.resolve("library");
        store.save(libraryDir, new LibraryTile(metadata("solo"), List.of(agent("a", List.of())),
                Map.of("prompts/a.md", "v1")));
        store.save(libraryDir, new LibraryTile(metadata("solo"), List.of(agent("a", List.of())),
                Map.of("prompts/a-new.md", "v2")));

        LibraryTile reloaded = store.read(libraryDir, "solo").orElseThrow();
        assertThat(reloaded.files()).containsExactly(Map.entry("prompts/a-new.md", "v2"));
    }

    @Test
    void libraryScopeDirectoriesMatchFR29(@TempDir Path root) {
        assertThat(LibraryScope.PROJECT.directory(root)).isEqualTo(root.resolve(".forgeide").resolve("library"));
        assertThat(LibraryScope.USER.directory(root).toString())
                .isEqualTo(Path.of(System.getProperty("user.home"), ".forgeide", "library").toString());
    }
}
