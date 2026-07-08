package dev.forgeide.ui.project;

import dev.forgeide.core.pipeline.validation.PipelineError;
import dev.forgeide.core.pipeline.yaml.PipelineTemplates;
import dev.forgeide.core.pipeline.yaml.PipelineYaml;
import dev.forgeide.core.port.RuntimeAvailabilityChecker;
import dev.forgeide.core.port.TileValidityChecker;
import dev.forgeide.core.project.ProjectDefinition;
import dev.forgeide.core.project.json.ProjectRegistry;
import dev.forgeide.ui.canvas.PipelineCanvasView;
import javafx.scene.Parent;
import javafx.scene.layout.BorderPane;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Swaps the project screens (list / form / detail / canvas) in-place, no separate windows. */
public final class ProjectsController {

    private final ProjectRegistry registry;
    private final RuntimeAvailabilityChecker checker;
    private final BorderPane root = new BorderPane();

    public ProjectsController(ProjectRegistry registry, RuntimeAvailabilityChecker checker) {
        this.registry = registry;
        this.checker = checker;
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
                () -> showForm(Optional.of(project)), this::showList, () -> showCanvas(project)));
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
