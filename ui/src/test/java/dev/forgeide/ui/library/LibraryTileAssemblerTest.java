package dev.forgeide.ui.library;

import dev.forgeide.core.pipeline.AgentStep;
import dev.forgeide.core.pipeline.JudgeStep;
import dev.forgeide.core.pipeline.ScriptStep;
import dev.forgeide.core.policy.FailPolicy;
import dev.forgeide.core.policy.RetryPolicy;
import dev.forgeide.core.policy.TokenBudget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** T23/FR-2.9: reads back exactly the files {@code LibraryTileInsertion} would need to relocate,
 * keyed the same way {@link dev.forgeide.core.pipeline.library.LibraryTile#files()} expects. */
class LibraryTileAssemblerTest {

    @Test
    void collectsAnAgentStepsPromptFile(@TempDir Path projectRoot) throws IOException {
        write(projectRoot, "prompts/lite-ground.md", "do the thing");
        AgentStep agent = new AgentStep("lite-ground", List.of(), "claude", Path.of("prompts/lite-ground.md"),
                List.of(), List.of(), List.of(), RetryPolicy.DEFAULT, TokenBudget.DEFAULT);

        Map<String, String> files = LibraryTileAssembler.collectFiles(projectRoot, List.of(agent));

        assertThat(files).containsExactly(Map.entry("prompts/lite-ground.md", "do the thing"));
    }

    @Test
    void collectsAJudgesLlmPromptAndDeterministicCheckScript(@TempDir Path projectRoot) throws IOException {
        write(projectRoot, "prompts/judge.md", "judge prompt");
        write(projectRoot, ".gigacode/skills/forgelite/scripts/check.py", "print('ok')");
        AgentStep llm = new AgentStep("judge.llm", List.of(), "claude", Path.of("prompts/judge.md"),
                List.of(), List.of(), List.of(), RetryPolicy.DEFAULT, TokenBudget.DEFAULT);
        ScriptStep check = new ScriptStep("judge.check", List.of(),
                List.of("python3", ".gigacode/skills/forgelite/scripts/check.py"), Duration.ofMinutes(5));
        JudgeStep judge = new JudgeStep("judge", List.of(), "lite-ground",
                Optional.of(llm), Optional.of(check), FailPolicy.DEFAULT);

        Map<String, String> files = LibraryTileAssembler.collectFiles(projectRoot, List.of(judge));

        assertThat(files).containsEntry("prompts/judge.md", "judge prompt");
        assertThat(files).containsEntry(".gigacode/skills/forgelite/scripts/check.py", "print('ok')");
    }

    @Test
    void skipsAMissingPromptFileInsteadOfFailing(@TempDir Path projectRoot) {
        AgentStep agent = new AgentStep("lite-ground", List.of(), "claude", Path.of("prompts/missing.md"),
                List.of(), List.of(), List.of(), RetryPolicy.DEFAULT, TokenBudget.DEFAULT);

        Map<String, String> files = LibraryTileAssembler.collectFiles(projectRoot, List.of(agent));

        assertThat(files).isEmpty();
    }

    @Test
    void ignoresAnEmptyPromptPath(@TempDir Path projectRoot) {
        AgentStep agent = new AgentStep("fresh", List.of(), "claude", Path.of(""),
                List.of(), List.of(), List.of(), RetryPolicy.DEFAULT, TokenBudget.DEFAULT);

        Map<String, String> files = LibraryTileAssembler.collectFiles(projectRoot, List.of(agent));

        assertThat(files).isEmpty();
    }

    private static void write(Path root, String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
