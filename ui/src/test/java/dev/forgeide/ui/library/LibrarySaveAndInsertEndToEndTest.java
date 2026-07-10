package dev.forgeide.ui.library;

import dev.forgeide.core.pipeline.AgentStep;
import dev.forgeide.core.pipeline.GateStep;
import dev.forgeide.core.pipeline.JudgeStep;
import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.ScriptStep;
import dev.forgeide.core.pipeline.StepDefinition;
import dev.forgeide.core.pipeline.library.LibraryTile;
import dev.forgeide.core.pipeline.library.LibraryTileInsertion;
import dev.forgeide.core.pipeline.library.LibraryTileMetadata;
import dev.forgeide.core.pipeline.library.TileLibraryStore;
import dev.forgeide.core.pipeline.validation.PipelineError;
import dev.forgeide.core.pipeline.validation.PipelineValidator;
import dev.forgeide.core.policy.FailPolicy;
import dev.forgeide.core.policy.RetryPolicy;
import dev.forgeide.core.policy.TokenBudget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T23 acceptance: "подграф (agent+judge+gate) сохранён в пользовательскую библиотеку и вставлен
 * в другой проект без ручной правки путей". Exercises the full save → store → insert round trip
 * across two independent temp "projects" and proves the result is immediately valid in the
 * target project — the same {@link PipelineValidator} the canvas's live validation uses.
 */
class LibrarySaveAndInsertEndToEndTest {

    private final TileLibraryStore store = new TileLibraryStore();

    @Test
    void savedSubgraphInsertsCleanlyIntoAnUnrelatedProject(@TempDir Path tmp) throws IOException {
        Path sourceProject = Files.createDirectory(tmp.resolve("source-project"));
        Path libraryDir = Files.createDirectory(tmp.resolve("user-library")); // stands in for ~/.forgeide/library
        Path targetProject = Files.createDirectory(tmp.resolve("target-project"));

        // ---- 1. build the subgraph as it would exist on a real canvas, with real files on disk
        write(sourceProject, "prompts/lite-green.md", "# do the green phase");
        write(sourceProject, ".gigacode/skills/forgelite/scripts/check_coverage.py", "print('coverage ok')");

        AgentStep agent = new AgentStep("lite-green", List.of(), "claude", Path.of("prompts/lite-green.md"),
                List.of(Path.of("src/main/**")), List.of("src/**"), List.of(), RetryPolicy.DEFAULT, TokenBudget.DEFAULT);
        ScriptStep check = new ScriptStep("judge-coverage.check", List.of(),
                List.of("python3", ".gigacode/skills/forgelite/scripts/check_coverage.py"), Duration.ofMinutes(10));
        JudgeStep judge = new JudgeStep("judge-coverage", List.of("lite-green"), "lite-green",
                Optional.empty(), Optional.of(check), FailPolicy.DEFAULT);
        GateStep gate = new GateStep("gate-deliver", List.of("judge-coverage"), "Deliver?", List.of("yes", "no"), List.of());
        List<StepDefinition> subgraph = List.of(agent, judge, gate);

        // ---- 2. save to the (user) library, exactly like PipelineConstructorView#saveSelectionToLibrary
        Map<String, String> files = LibraryTileAssembler.collectFiles(sourceProject, subgraph);
        LibraryTileMetadata metadata = new LibraryTileMetadata("agent-judge-gate", "Green + coverage judge + deliver gate",
                "camiah", Optional.empty(), List.of("agent", "judge", "gate"), Instant.now());
        store.save(libraryDir, new LibraryTile(metadata, subgraph, files));

        // ---- 3. insert into a completely different, empty project
        LibraryTile reloaded = store.read(libraryDir, "agent-judge-gate").orElseThrow();
        LibraryTileInsertion.Result result = LibraryTileInsertion.insert(reloaded, Set.of());
        result.files().forEach((relativePath, content) -> write(targetProject, relativePath.toString(), content));

        // ---- 4. the inserted steps must be a self-contained, immediately valid mini-pipeline —
        //         no dangling references, no missing prompt/script files, in the TARGET project.
        PipelineDefinition inserted = new PipelineDefinition("pipeline", 1, result.steps());
        List<PipelineError> errors = new PipelineValidator().validate(inserted, PipelineValidator.Options.withRoot(targetProject));
        assertThat(errors).isEmpty();

        // ---- 5. and it never touched the source project or reused its file layout verbatim
        StepDefinition newAgent = inserted.steps().get(0);
        assertThat(((AgentStep) newAgent).promptTemplate()).isNotEqualTo(Path.of("prompts/lite-green.md"));
        assertThat(Files.isRegularFile(sourceProject.resolve("prompts/lite-green.md"))).isTrue(); // untouched
        assertThat(newAgent.id()).isNotEqualTo("lite-green"); // id was regenerated, not copied verbatim

        // ---- 6. the judge's check script also landed at a real, working path in the target
        //         project — PipelineValidator itself doesn't check script files, so assert directly.
        JudgeStep newJudge = (JudgeStep) inserted.steps().get(1);
        List<String> newCommand = newJudge.deterministicCheck().orElseThrow().command();
        Path scriptPath = Path.of(newCommand.get(1));
        assertThat(Files.isRegularFile(targetProject.resolve(scriptPath))).isTrue();
        assertThat(Files.readString(targetProject.resolve(scriptPath))).isEqualTo("print('coverage ok')");
    }

    private static void write(Path root, String relative, String content) {
        try {
            Path file = root.resolve(relative);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
