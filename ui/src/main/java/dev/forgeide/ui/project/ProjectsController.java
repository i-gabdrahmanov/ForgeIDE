package dev.forgeide.ui.project;

import dev.forgeide.core.port.RuntimeAvailabilityChecker;
import dev.forgeide.core.project.ProjectDefinition;
import dev.forgeide.core.project.json.ProjectRegistry;
import javafx.scene.Parent;
import javafx.scene.layout.BorderPane;

import java.util.Optional;

/** Swaps the three project screens (list / form / detail) in-place, no separate windows. */
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
        root.setCenter(new ProjectListView(registry.list(), this::showDetail, () -> showForm(Optional.empty())));
    }

    public void showForm(Optional<ProjectDefinition> editing) {
        root.setCenter(new ProjectFormView(editing, checker, this::saveAndShowDetail, this::showList));
    }

    public void showDetail(ProjectDefinition project) {
        root.setCenter(new ProjectDetailView(project, checker,
                () -> showForm(Optional.of(project)), this::showList));
    }

    private void saveAndShowDetail(ProjectDefinition project) {
        ProjectDefinition saved = registry.save(project);
        showDetail(saved);
    }
}
