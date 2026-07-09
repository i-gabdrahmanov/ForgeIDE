package dev.forgeide.ui.project;

import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.validation.PipelineError;
import dev.forgeide.core.pipeline.yaml.PipelineTemplates;
import dev.forgeide.core.pipeline.yaml.PipelineYaml;
import dev.forgeide.core.engine.PipelineEngine;
import dev.forgeide.core.port.RuntimeAvailabilityChecker;
import dev.forgeide.core.port.StateStore;
import dev.forgeide.core.port.TileValidityChecker;
import dev.forgeide.core.project.ProjectDefinition;
import dev.forgeide.core.project.json.ProjectRegistry;
import dev.forgeide.core.run.RunId;
import dev.forgeide.core.run.RunSnapshot;
import dev.forgeide.runtime.agent.CompositeAgentRuntime;
import dev.forgeide.runtime.script.ScriptExecutor;
import dev.forgeide.runtime.state.FileStateStore;
import dev.forgeide.runtime.state.ManifestProjector;
import dev.forgeide.runtime.state.ProjectHash;
import dev.forgeide.ui.canvas.PipelineCanvasView;
import dev.forgeide.ui.run.RunEngineRegistry;
import dev.forgeide.ui.run.RunHistoryDialog;
import dev.forgeide.ui.run.RunView;
import dev.forgeide.ui.run.StartRunDialog;
import javafx.scene.Parent;
import javafx.scene.layout.BorderPane;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Swaps the project screens (list / form / detail / canvas / run) in-place, no separate windows. */
public final class ProjectsController {

    private final ProjectRegistry registry;
    private final RuntimeAvailabilityChecker checker;
    private final RunEngineRegistry engineRegistry;
    private final BorderPane root = new BorderPane();

    public ProjectsController(ProjectRegistry registry, RuntimeAvailabilityChecker checker) {
        this(registry, checker, new RunEngineRegistry());
    }

    public ProjectsController(ProjectRegistry registry, RuntimeAvailabilityChecker checker,
                               RunEngineRegistry engineRegistry) {
        this.registry = registry;
        this.checker = checker;
        this.engineRegistry = engineRegistry;
        showList();
    }

    public Parent root() {
        return root;
    }

    public void showList() {
        root.setCenter(new ProjectListView(registry.list(), this::showDetail, () -> showForm(Optional.empty()),
                this::showForgeliteTemplate));
    }

    public void showForm(Optional<ProjectDefinition> editing) {
        root.setCenter(new ProjectFormView(editing, checker, this::saveAndShowDetail, this::showList));
    }

    public void showDetail(ProjectDefinition project) {
        root.setCenter(new ProjectDetailView(project, checker,
                () -> showForm(Optional.of(project)), this::showList, () -> showCanvas(project),
                () -> showStartRun(project), () -> showRunHistory(project)));
    }

    /** Feature-slug + readiness form (SDD FR-7.9's launch flow); "Start" hands off to {@link #showRun}. */
    public void showStartRun(ProjectDefinition project) {
        root.setCenter(new StartRunDialog(project, () -> showDetail(project),
                (pipeline, featureSlug) -> showRun(project, pipeline, featureSlug)));
    }

    /**
     * Entry point into a feature's run history independent of starting a new run (T11): lets the
     * user find a run FR-3.4 recovery left with an interrupted step and resume it, without having
     * to launch a brand new one first.
     */
    public void showRunHistory(ProjectDefinition project) {
        Path pipelinePath = project.repositoryPath().resolve(".forgeide").resolve("pipeline.yaml");
        if (!Files.isRegularFile(pipelinePath)) {
            showCanvas(project); // reuses its own "no pipeline.yaml at ..." error banner
            return;
        }
        PipelineYaml.ParseResult parsed = new PipelineYaml().parseLenient(pipelinePath);
        if (parsed.definition().isEmpty()) {
            showCanvas(project); // reuses its own parse-error banner
            return;
        }
        PipelineDefinition pipeline = parsed.definition().get();
        String projectHash = ProjectHash.of(project.repositoryPath());
        StateStore stateStore = new FileStateStore(FileStateStore.defaultRoot(projectHash, pipeline.id()));
        root.setCenter(new RunHistoryDialog(project, stateStore, () -> showDetail(project),
                snapshot -> showResumedRun(project, pipeline, stateStore, snapshot)));
    }

    /** Reattaches a live {@link PipelineEngine} to a run persisted by an earlier process (T11
     * FR-3.4) — e.g. one {@code StartupRecovery} just turned an abandoned step terminal. */
    private void showResumedRun(ProjectDefinition project, PipelineDefinition pipeline, StateStore stateStore,
                                 RunSnapshot snapshot) {
        PipelineEngine engine = new PipelineEngine(stateStore, CompositeAgentRuntime.claudeAndGigacode(),
                new ScriptExecutor(), new ManifestProjector());
        RunId runId = snapshot.runId();
        engine.resume(project, pipeline, runId);
        engineRegistry.register(runId, engine);
        root.setCenter(new RunView(engine, stateStore, project, pipeline, runId, snapshot.featureSlug(),
                () -> showDetail(project)));
    }

    /**
     * Launches a real run: one {@link PipelineEngine} per (project, pipeline) — {@link
     * FileStateStore}'s root is fixed at construction, so a single app-wide engine can't serve
     * every open project (T10 plan). {@link RunEngineRegistry} keeps it alive until the run
     * reaches a terminal status, even if the user navigates back to the project list.
     */
    public void showRun(ProjectDefinition project, PipelineDefinition pipeline, String featureSlug) {
        String projectHash = ProjectHash.of(project.repositoryPath());
        StateStore stateStore = new FileStateStore(FileStateStore.defaultRoot(projectHash, pipeline.id()));
        PipelineEngine engine = new PipelineEngine(stateStore, CompositeAgentRuntime.claudeAndGigacode(),
                new ScriptExecutor(), new ManifestProjector());
        RunId runId = engine.start(project, pipeline, featureSlug);
        engineRegistry.register(runId, engine);

        // RunView disposes its own RunViewModel/tailers before invoking this callback (its back
        // button), so this only needs to decide what screen comes next.
        root.setCenter(new RunView(engine, stateStore, project, pipeline, runId, featureSlug,
                () -> showDetail(project)));
    }

    /** M1 acceptance: "открыть шаблон forgelite → граф на канвасе", no project required. */
    public void showForgeliteTemplate() {
        PipelineYaml.ParseResult result = new PipelineYaml().parseLenient(PipelineTemplates.forgeliteYaml());
        root.setCenter(new PipelineCanvasView("forgelite (bundled template)", result,
                TileValidityChecker.unknown(), this::showList));
    }

    /** Opens {@code <repo>/.forgeide/pipeline.yaml}; badges render even if it is invalid (FR-2.3). */
    public void showCanvas(ProjectDefinition project) {
        Path pipelinePath = project.repositoryPath().resolve(".forgeide").resolve("pipeline.yaml");
        PipelineYaml.ParseResult result = Files.isRegularFile(pipelinePath)
                ? new PipelineYaml().parseLenient(pipelinePath)
                : new PipelineYaml.ParseResult(Optional.empty(),
                        List.of(PipelineError.atPipeline("", "no pipeline.yaml at " + pipelinePath)));
        root.setCenter(new PipelineCanvasView(project.name(), result, TileValidityChecker.unknown(),
                () -> showDetail(project)));
    }

    private void saveAndShowDetail(ProjectDefinition project) {
        ProjectDefinition saved = registry.save(project);
        showDetail(saved);
    }
}
