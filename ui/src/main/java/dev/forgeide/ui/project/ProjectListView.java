package dev.forgeide.ui.project;

import dev.forgeide.core.project.ProjectDefinition;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;

import java.util.List;
import java.util.function.Consumer;

/** Screen listing registered projects (SDD FR-1.1, T04 acceptance: open a project). */
public final class ProjectListView extends BorderPane {

    public ProjectListView(List<ProjectDefinition> projects, Consumer<ProjectDefinition> onOpen, Runnable onCreate) {
        Label title = new Label("Projects");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        Button newProject = new Button("New project");
        newProject.setOnAction(e -> onCreate.run());
        HBox header = new HBox(12, title, newProject);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(12));
        setTop(header);
        setPadding(new Insets(0, 12, 12, 12));

        if (projects.isEmpty()) {
            Label empty = new Label("No projects yet — create one to get started.");
            empty.setPadding(new Insets(12));
            setCenter(empty);
            return;
        }

        ListView<ProjectDefinition> list = new ListView<>(FXCollections.observableArrayList(projects));
        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(ProjectDefinition item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null
                        : item.name() + "  —  " + item.repositoryPath() + "  [" + item.criticality() + "]");
            }
        });
        list.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                ProjectDefinition selected = list.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    onOpen.accept(selected);
                }
            }
        });
        setCenter(list);
    }
}
