package dev.forgeide.ui.library;

import dev.forgeide.core.pipeline.AgentStep;
import dev.forgeide.core.pipeline.BranchStep;
import dev.forgeide.core.pipeline.GateStep;
import dev.forgeide.core.pipeline.JudgeStep;
import dev.forgeide.core.pipeline.OutwardStep;
import dev.forgeide.core.pipeline.PerTaskLoop;
import dev.forgeide.core.pipeline.ScriptStep;
import dev.forgeide.core.pipeline.StepDefinition;
import dev.forgeide.runtime.harness.JudgeScriptLocator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T23/FR-2.9 "save to library" — the read side, mirroring {@code LibraryTileInsertion}'s write
 * side: walks the steps a user selected on the canvas and reads every prompt/script file they
 * reference off disk, keyed by the same project-relative path {@link
 * dev.forgeide.core.pipeline.library.LibraryTile#files()} expects. Needs {@code projectRoot} and
 * {@link JudgeScriptLocator} (runtime), which is why this lives in {@code ui}/{@code runtime}
 * territory rather than the pure {@code core.pipeline.library} model classes.
 */
public final class LibraryTileAssembler {

    private LibraryTileAssembler() {
    }

    public static Map<String, String> collectFiles(Path projectRoot, List<StepDefinition> steps) {
        Map<String, String> files = new LinkedHashMap<>();
        for (StepDefinition step : steps) {
            collectFromStep(projectRoot, step, files);
        }
        return files;
    }

    private static void collectFromStep(Path projectRoot, StepDefinition step, Map<String, String> files) {
        switch (step) {
            case AgentStep a -> addPrompt(projectRoot, a.promptTemplate(), files);
            case ScriptStep s -> addScript(projectRoot, s.command(), files);
            case JudgeStep j -> {
                j.llmJudge().ifPresent(l -> addPrompt(projectRoot, l.promptTemplate(), files));
                j.deterministicCheck().ifPresent(c -> addScript(projectRoot, c.command(), files));
            }
            case GateStep ignored -> {
            }
            case BranchStep ignored -> {
            }
            case PerTaskLoop l -> l.template().forEach(nested -> collectFromStep(projectRoot, nested, files));
            case OutwardStep ignored -> {
            }
        }
    }

    private static void addPrompt(Path projectRoot, Path relative, Map<String, String> files) {
        if (relative.toString().isBlank()) {
            return;
        }
        Path absolute = projectRoot.resolve(relative);
        if (Files.isRegularFile(absolute)) {
            files.put(relative.toString(), readString(absolute));
        }
    }

    private static void addScript(Path projectRoot, List<String> command, Map<String, String> files) {
        JudgeScriptLocator.locate(projectRoot, command).ifPresent(relative -> {
            Path absolute = projectRoot.resolve(relative);
            if (Files.isRegularFile(absolute)) {
                files.put(relative.toString(), readString(absolute));
            }
        });
    }

    private static String readString(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
    }
}
