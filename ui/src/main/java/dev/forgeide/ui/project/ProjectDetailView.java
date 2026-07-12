package dev.forgeide.ui.project;

import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.yaml.PipelineYaml;
import dev.forgeide.core.port.HarnessGuardPort;
import dev.forgeide.core.port.RuntimeAvailabilityChecker;
import dev.forgeide.core.project.ProjectDefinition;
import dev.forgeide.core.project.ProjectParamValidator;
import dev.forgeide.core.project.RuntimeAvailability;
import dev.forgeide.core.project.RuntimeBinding;
import dev.forgeide.core.project.RuntimeStatus;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Opened-project screen (T04 acceptance): shows every persisted field, live runtime
 * availability per binding, and — when a pipeline.yaml is present — a readiness warning for
 * missing required params. Actually starting a run is out of scope here (T06+).
 */
public final class ProjectDetailView extends BorderPane {

    public ProjectDetailView(ProjectDefinition project, RuntimeAvailabilityChecker checker,
                              HarnessGuardPort harnessGuard, boolean autoDeployHarness,
                              Runnable onEdit, Runnable onBack, Runnable onOpenCanvas, Runnable onStartRun,
                              Runnable onRunHistory, Runnable onImport) {
        Button back = new Button("← Projects");
        back.setOnAction(e -> onBack.run());
        Button edit = new Button("Edit");
        edit.setOnAction(e -> onEdit.run());
        Button openCanvas = new Button("Open canvas");
        openCanvas.setOnAction(e -> onOpenCanvas.run());
        Button startRun = new Button("Start run");
        startRun.setOnAction(e -> onStartRun.run());
        Button runHistory = new Button("Run history");
        runHistory.setOnAction(e -> onRunHistory.run());
        Button importScaffold = new Button("Import scaffold");
        importScaffold.setOnAction(e -> onImport.run());
        HBox header = new HBox(12, back, edit, openCanvas, startRun, runHistory, importScaffold);
        header.setPadding(new Insets(12));
        setTop(header);

        VBox content = new VBox(12);
        content.setPadding(new Insets(16));

        Label title = new Label(project.name());
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        Label repo = new Label("Repository: " + project.repositoryPath());
        Label criticality = new Label("Criticality: " + project.criticality()
                + "  (auto_max_risk " + project.criticality().maxRisk() + ")");
        content.getChildren().addAll(title, repo, criticality);

        content.getChildren().add(labelledList("Specs", project.specPaths().stream().map(Path::toString).toList()));
        content.getChildren().add(runtimesSection(project, checker));
        content.getChildren().add(harnessSection(project, harnessGuard, autoDeployHarness));
        content.getChildren().add(labelledList("Params",
                project.paramValues().entrySet().stream().map(en -> en.getKey() + " = " + en.getValue()).toList()));

        readinessWarning(project).ifPresent(content.getChildren()::add);

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        setCenter(scroll);
    }

    private VBox runtimesSection(ProjectDefinition project, RuntimeAvailabilityChecker checker) {
        VBox box = new VBox(6, sectionLabel("Runtimes"));
        if (project.runtimes().isEmpty()) {
            box.getChildren().add(new Label("(none configured)"));
            return box;
        }
        for (RuntimeBinding runtime : project.runtimes()) {
            Label status = new Label("checking…");
            box.getChildren().add(new HBox(8,
                    new Label(runtime.name() + "  (" + runtime.binaryPath() + ")"), status));
            Thread.ofVirtual().start(() -> {
                RuntimeAvailability result = checker.check(runtime);
                Platform.runLater(() -> {
                    status.setText(result.status() + (result.detail().isBlank() ? "" : ": " + result.detail()));
                    status.setTextFill(result.status() == RuntimeStatus.AVAILABLE ? Color.SEAGREEN : Color.FIREBRICK);
                });
            });
        }
        return box;
    }

    /**
     * T37: "Deploy harness" button + status line + (on a failed preflight) a bullet list of
     * {@code preflight.py}'s problems — the only UI entry point onto {@link
     * HarnessGuardPort#deploy}, which until now nothing in {@code ui} ever called (a fresh
     * project's first run had no way past {@code HARNESS_PREFLIGHT} except the T20 trusted-edit
     * workaround {@code docs/manual.md} §5 used to describe). {@code autoDeployHarness} fires the
     * same click handler once, right after the section is built, for the "just imported — deploy
     * now?" flow ({@link ProjectsController#showImport}) — same code path as a manual click, so
     * there is exactly one place that talks to {@link HarnessGuardPort#deploy}.
     */
    private VBox harnessSection(ProjectDefinition project, HarnessGuardPort harnessGuard, boolean autoDeployHarness) {
        VBox box = new VBox(6, sectionLabel("Harness"));
        Label status = new Label("checking…");
        Button deploy = new Button("Deploy harness");
        VBox problems = new VBox(2);
        box.getChildren().addAll(new HBox(8, deploy, status), problems);

        deploy.setOnAction(e -> {
            deploy.setDisable(true);
            status.setText("deploying…");
            status.setTextFill(Color.BLACK);
            problems.getChildren().clear();
            Thread.ofVirtual().start(() -> {
                HarnessGuardPort.DeployResult result = harnessGuard.deploy(project.repositoryPath());
                Platform.runLater(() -> {
                    deploy.setDisable(false);
                    applyHarnessStatus(status, problems, result.preflightPassed(), result.preflightOutput(),
                            Optional.of(Instant.now()));
                });
            });
        });

        Thread.ofVirtual().start(() -> {
            HarnessGuardPort.PreflightStatus current = harnessGuard.preflightStatus(project.repositoryPath());
            Platform.runLater(() -> applyHarnessStatus(status, problems, current.passed(), current.detail(),
                    current.deployedAt()));
        });
        if (autoDeployHarness) {
            deploy.fire();
        }
        return box;
    }

    private void applyHarnessStatus(Label status, VBox problems, boolean passed, String detail,
                                     Optional<Instant> deployedAt) {
        status.setText(HarnessStatusText.summary(passed, deployedAt));
        status.setTextFill(passed ? Color.SEAGREEN : Color.FIREBRICK);
        problems.getChildren().setAll(HarnessStatusText.problems(passed, detail).stream()
                .map(line -> {
                    Label p = new Label("• " + line);
                    p.setTextFill(Color.FIREBRICK);
                    return p;
                })
                .toList());
    }

    private VBox labelledList(String label, List<String> lines) {
        VBox box = new VBox(2, sectionLabel(label));
        if (lines.isEmpty()) {
            box.getChildren().add(new Label("(none)"));
        } else {
            lines.forEach(line -> box.getChildren().add(new Label(line)));
        }
        return box;
    }

    private Label sectionLabel(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-weight: bold;");
        return label;
    }

    private Optional<Label> readinessWarning(ProjectDefinition project) {
        Path pipelinePath = project.repositoryPath().resolve(".forgeide").resolve("pipeline.yaml");
        if (!Files.isRegularFile(pipelinePath)) {
            return Optional.empty();
        }
        try {
            PipelineDefinition pipeline = new PipelineYaml().parse(pipelinePath);
            List<String> missing = ProjectParamValidator.missingRequiredParams(project, pipeline);
            if (missing.isEmpty()) {
                return Optional.empty();
            }
            Label warning = new Label("Missing required params — a run would be blocked: "
                    + String.join(", ", missing));
            warning.setTextFill(Color.FIREBRICK);
            return Optional.of(warning);
        } catch (RuntimeException ex) {
            Label warning = new Label("Could not validate pipeline.yaml: " + ex.getMessage());
            warning.setTextFill(Color.DARKORANGE);
            return Optional.of(warning);
        }
    }
}
