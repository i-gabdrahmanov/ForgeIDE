package dev.forgeide.ui.project;

import dev.forgeide.core.pipeline.PipelineParam;
import dev.forgeide.core.pipeline.yaml.PipelineYaml;
import dev.forgeide.core.port.RuntimeAvailabilityChecker;
import dev.forgeide.core.project.CriticalityProfile;
import dev.forgeide.core.project.ProjectDefinition;
import dev.forgeide.core.project.ProjectId;
import dev.forgeide.core.project.RuntimeAvailability;
import dev.forgeide.core.project.RuntimeBinding;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.util.converter.DefaultStringConverter;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Create/edit form (SDD FR-1.1, T04 acceptance: created project persists with all fields).
 * Param fields below the fold are generated from {@code <repo>/.forgeide/pipeline.yaml → params}
 * (required/hint) via "Reload params"; anything else the user types is kept as a free-form
 * key-value extra, per BT §4.1 ("форма расширяемая").
 */
public final class ProjectFormView extends BorderPane {

    private final TextField nameField = new TextField();
    private final TextField repoField = new TextField();
    private final ComboBox<CriticalityProfile> criticalityBox =
            new ComboBox<>(FXCollections.observableArrayList(CriticalityProfile.values()));
    private final ListView<Path> specPathsList = new ListView<>(FXCollections.observableArrayList());
    private final VBox runtimesBox = new VBox(6);
    private final List<RuntimeRow> runtimeRows = new ArrayList<>();
    private final VBox declaredParamsBox = new VBox(6);
    private final Map<String, TextField> declaredParamFields = new LinkedHashMap<>();
    private final Label pipelineStatus = new Label();
    private final TableView<ExtraParamRow> extraParamsTable = new TableView<>(FXCollections.observableArrayList());

    private final Optional<ProjectDefinition> editing;
    private final Map<String, String> initialParamValues;
    private final RuntimeAvailabilityChecker checker;

    public ProjectFormView(Optional<ProjectDefinition> editing, RuntimeAvailabilityChecker checker,
                            Consumer<ProjectDefinition> onSave, Runnable onCancel) {
        this.editing = editing;
        this.checker = checker;
        this.initialParamValues = new LinkedHashMap<>(editing.map(ProjectDefinition::paramValues).orElse(Map.of()));

        VBox content = new VBox(16,
                section("Name", nameRow()),
                section("Repository", repoRow()),
                section("Criticality profile", criticalityBox),
                section("Spec files", specPathsSection()),
                section("Runtimes", runtimesSection()),
                paramsSection());
        content.setPadding(new Insets(16));

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        setCenter(scroll);
        setBottom(actions(onSave, onCancel));

        editing.ifPresent(this::populate);
        reloadDeclaredParams();
    }

    // ---- sections -----------------------------------------------------------------------

    private Node nameRow() {
        nameField.setPromptText("my-project");
        return nameField;
    }

    private Node repoRow() {
        repoField.setPromptText("/path/to/repo");
        Button browse = new Button("Browse…");
        browse.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            File dir = chooser.showDialog(windowOf(browse));
            if (dir != null) {
                repoField.setText(dir.getAbsolutePath());
            }
        });
        HBox row = new HBox(8, repoField, browse);
        HBox.setHgrow(repoField, javafx.scene.layout.Priority.ALWAYS);
        return row;
    }

    private Node specPathsSection() {
        Button add = new Button("Add spec file…");
        add.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            List<File> files = chooser.showOpenMultipleDialog(windowOf(add));
            if (files != null) {
                files.forEach(f -> specPathsList.getItems().add(f.toPath()));
            }
        });
        Button remove = new Button("Remove selected");
        remove.setOnAction(e -> {
            Path selected = specPathsList.getSelectionModel().getSelectedItem();
            if (selected != null) {
                specPathsList.getItems().remove(selected);
            }
        });
        specPathsList.setPrefHeight(100);
        HBox buttons = new HBox(8, add, remove);
        return new VBox(6, specPathsList, buttons);
    }

    private Node runtimesSection() {
        Button add = new Button("Add runtime");
        add.setOnAction(e -> addRuntimeRow(null));
        return new VBox(6, runtimesBox, add);
    }

    private void addRuntimeRow(RuntimeBinding existing) {
        RuntimeRow row = new RuntimeRow(existing, checker, () -> {
        });
        row.removeButton.setOnAction(e -> {
            runtimeRows.remove(row);
            runtimesBox.getChildren().remove(row);
        });
        runtimeRows.add(row);
        runtimesBox.getChildren().add(row);
    }

    private Node paramsSection() {
        Button reload = new Button("Reload params from pipeline.yaml");
        reload.setOnAction(e -> reloadDeclaredParams());
        HBox reloadRow = new HBox(8, reload, pipelineStatus);
        reloadRow.setAlignment(Pos.CENTER_LEFT);

        extraParamsTable.setEditable(true);
        extraParamsTable.setPrefHeight(140);
        TableColumn<ExtraParamRow, String> keyCol = new TableColumn<>("key");
        keyCol.setCellValueFactory(data -> data.getValue().key);
        keyCol.setCellFactory(TextFieldTableCell.forTableColumn(new DefaultStringConverter()));
        keyCol.setOnEditCommit(e -> e.getRowValue().key.set(e.getNewValue()));
        TableColumn<ExtraParamRow, String> valueCol = new TableColumn<>("value");
        valueCol.setCellValueFactory(data -> data.getValue().value);
        valueCol.setCellFactory(TextFieldTableCell.forTableColumn(new DefaultStringConverter()));
        valueCol.setOnEditCommit(e -> e.getRowValue().value.set(e.getNewValue()));
        extraParamsTable.getColumns().addAll(List.of(keyCol, valueCol));

        Button addExtra = new Button("Add param");
        addExtra.setOnAction(e -> extraParamsTable.getItems().add(new ExtraParamRow("", "")));
        Button removeExtra = new Button("Remove selected");
        removeExtra.setOnAction(e -> {
            ExtraParamRow selected = extraParamsTable.getSelectionModel().getSelectedItem();
            if (selected != null) {
                extraParamsTable.getItems().remove(selected);
            }
        });

        return new VBox(6,
                sectionLabel("Params"),
                declaredParamsBox,
                reloadRow,
                sectionLabel("Extra params (free-form key-value)"),
                extraParamsTable,
                new HBox(8, addExtra, removeExtra));
    }

    private Node actions(Consumer<ProjectDefinition> onSave, Runnable onCancel) {
        Button save = new Button("Save");
        save.setDefaultButton(true);
        save.setOnAction(e -> {
            try {
                onSave.accept(buildProject());
            } catch (RuntimeException ex) {
                new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait();
            }
        });
        Button cancel = new Button("Cancel");
        cancel.setOnAction(e -> onCancel.run());
        HBox box = new HBox(8, save, cancel);
        box.setAlignment(Pos.CENTER_RIGHT);
        box.setPadding(new Insets(12));
        return box;
    }

    // ---- pipeline.yaml driven params ------------------------------------------------------

    private void reloadDeclaredParams() {
        Map<String, String> previous = new LinkedHashMap<>(initialParamValues);
        previous.putAll(collectParamValues());

        declaredParamFields.clear();
        declaredParamsBox.getChildren().clear();
        List<PipelineParam> declaredParams = List.of();

        Path repo = currentRepositoryPath();
        if (repo == null) {
            pipelineStatus.setText("Set a repository path first.");
        } else {
            Path pipelinePath = repo.resolve(".forgeide").resolve("pipeline.yaml");
            if (!Files.isRegularFile(pipelinePath)) {
                pipelineStatus.setText("No pipeline.yaml at " + pipelinePath);
            } else {
                try {
                    declaredParams = new PipelineYaml().parse(pipelinePath).params();
                    pipelineStatus.setText(declaredParams.size() + " param(s) declared");
                } catch (RuntimeException ex) {
                    pipelineStatus.setText("Could not read pipeline.yaml: " + ex.getMessage());
                }
            }
        }

        for (PipelineParam param : declaredParams) {
            Label label = new Label((param.required() ? "* " : "") + param.name());
            param.hint().ifPresent(hint -> Tooltip.install(label, new Tooltip(hint)));
            TextField field = new TextField(previous.getOrDefault(param.name(), ""));
            declaredParamFields.put(param.name(), field);
            HBox row = new HBox(8, label, field);
            HBox.setHgrow(field, javafx.scene.layout.Priority.ALWAYS);
            declaredParamsBox.getChildren().add(row);
        }

        Set<String> declaredNames = declaredParams.stream().map(PipelineParam::name).collect(Collectors.toSet());
        extraParamsTable.getItems().setAll(previous.entrySet().stream()
                .filter(entry -> !declaredNames.contains(entry.getKey()))
                .map(entry -> new ExtraParamRow(entry.getKey(), entry.getValue()))
                .toList());
    }

    private Map<String, String> collectParamValues() {
        Map<String, String> values = new LinkedHashMap<>();
        declaredParamFields.forEach((name, field) -> values.put(name, field.getText()));
        for (ExtraParamRow row : extraParamsTable.getItems()) {
            String key = row.key.get();
            if (key != null && !key.isBlank()) {
                values.put(key, row.value.get());
            }
        }
        return values;
    }

    private Path currentRepositoryPath() {
        String text = repoField.getText();
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Path.of(text.trim());
        } catch (RuntimeException ex) {
            return null;
        }
    }

    // ---- populate / build -----------------------------------------------------------------

    private void populate(ProjectDefinition project) {
        nameField.setText(project.name());
        repoField.setText(project.repositoryPath().toString());
        criticalityBox.setValue(project.criticality());
        specPathsList.getItems().setAll(project.specPaths());
        project.runtimes().forEach(this::addRuntimeRow);
    }

    private ProjectDefinition buildProject() {
        String name = nameField.getText() == null ? "" : nameField.getText().trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Project name must not be blank.");
        }
        String repoText = repoField.getText() == null ? "" : repoField.getText().trim();
        if (repoText.isEmpty()) {
            throw new IllegalArgumentException("Repository path must not be blank.");
        }
        Path repo = Path.of(repoText);
        List<Path> specPaths = List.copyOf(specPathsList.getItems());
        Map<String, String> paramValues = collectParamValues();
        CriticalityProfile criticality = criticalityBox.getValue() == null
                ? CriticalityProfile.MEDIUM : criticalityBox.getValue();
        List<RuntimeBinding> runtimes = runtimeRows.stream()
                .filter(row -> !row.isBlank())
                .map(RuntimeRow::toBinding)
                .toList();
        ProjectId id = editing.map(ProjectDefinition::id).orElseGet(ProjectId::newId);
        return new ProjectDefinition(id, name, repo, specPaths, paramValues, criticality, runtimes);
    }

    // ---- layout helpers ---------------------------------------------------------------

    private Node section(String label, Node body) {
        return new VBox(4, sectionLabel(label), body);
    }

    private Label sectionLabel(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-weight: bold;");
        return label;
    }

    private javafx.stage.Window windowOf(Node node) {
        return node.getScene() == null ? null : node.getScene().getWindow();
    }

    // ---- inner rows ---------------------------------------------------------------------

    private static final class RuntimeRow extends HBox {
        final TextField name = new TextField();
        final TextField binaryPath = new TextField();
        final TextField flags = new TextField();
        final Label status = new Label();
        final Button removeButton = new Button("Remove");

        RuntimeRow(RuntimeBinding existing, RuntimeAvailabilityChecker checker, Runnable ignored) {
            setSpacing(6);
            setAlignment(Pos.CENTER_LEFT);
            name.setPromptText("name (e.g. claude)");
            binaryPath.setPromptText("/path/to/binary");
            flags.setPromptText("--experimental-hooks");
            if (existing != null) {
                name.setText(existing.name());
                binaryPath.setText(existing.binaryPath().toString());
                flags.setText(FlagsText.format(existing.flags()));
            }
            Button browse = new Button("…");
            browse.setOnAction(e -> {
                FileChooser chooser = new FileChooser();
                File file = chooser.showOpenDialog(getScene() == null ? null : getScene().getWindow());
                if (file != null) {
                    binaryPath.setText(file.getAbsolutePath());
                }
            });
            Button check = new Button("Check availability");
            check.setOnAction(e -> {
                try {
                    RuntimeAvailability result = checker.check(toBinding());
                    status.setText(result.status() + (result.detail().isBlank() ? "" : ": " + result.detail()));
                } catch (RuntimeException ex) {
                    status.setText("error: " + ex.getMessage());
                }
            });
            getChildren().addAll(name, binaryPath, browse, flags, check, status, removeButton);
        }

        RuntimeBinding toBinding() {
            return new RuntimeBinding(name.getText().trim(), Path.of(binaryPath.getText().trim()),
                    FlagsText.parse(flags.getText()));
        }

        boolean isBlank() {
            return name.getText() == null || name.getText().isBlank();
        }
    }

    private static final class ExtraParamRow {
        final StringProperty key;
        final StringProperty value;

        ExtraParamRow(String key, String value) {
            this.key = new SimpleStringProperty(key);
            this.value = new SimpleStringProperty(value);
        }
    }
}
