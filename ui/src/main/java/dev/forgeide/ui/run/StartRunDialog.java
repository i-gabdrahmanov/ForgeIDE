package dev.forgeide.ui.run;

import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.yaml.PipelineYaml;
import dev.forgeide.core.project.ProjectDefinition;
import dev.forgeide.core.project.ProjectParamValidator;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Minimal "start a run" form: a feature slug plus a Start button, gated on the same readiness
 * checks {@code ProjectDetailView} already surfaces (missing required params, an unparsable
 * pipeline.yaml) — so {@link RunView} only ever receives an already-valid {@link
 * PipelineDefinition} and a launched {@code RunId}.
 */
public final class StartRunDialog extends BorderPane {

    public StartRunDialog(ProjectDefinition project, Runnable onBack, BiConsumer<PipelineDefinition, String> onStart) {
        Button back = new Button("← Back");
        back.setOnAction(e -> onBack.run());
        Label title = new Label("Start run — " + project.name());
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        HBox header = new HBox(12, back, title);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(12));
        setTop(header);

        VBox content = new VBox(12);
        content.setPadding(new Insets(16));
        setCenter(content);

        Path pipelinePath = project.repositoryPath().resolve(".forgeide").resolve("pipeline.yaml");
        if (!Files.isRegularFile(pipelinePath)) {
            content.getChildren().add(errorLabel("No pipeline.yaml at " + pipelinePath));
            return;
        }

        PipelineYaml.ParseResult parsed = new PipelineYaml().parseLenient(pipelinePath);
        if (parsed.definition().isEmpty()) {
            content.getChildren().add(errorLabel("pipeline.yaml could not be parsed:"));
            parsed.errors().forEach(err -> content.getChildren().add(errorLabel("• " + err)));
            return;
        }
        PipelineDefinition pipeline = parsed.definition().get();

        TextField featureSlug = new TextField();
        featureSlug.setPromptText("feature-slug (e.g. a Jira ticket)");
        Button start = new Button("Start");

        content.getChildren().addAll(new Label("Feature slug"), featureSlug);

        List<String> missing = ProjectParamValidator.missingRequiredParams(project, pipeline);
        if (!missing.isEmpty()) {
            content.getChildren().add(errorLabel("Missing required params — a run would be blocked: "
                    + String.join(", ", missing)));
            start.setDisable(true);
        }

        start.setOnAction(e -> {
            String slug = featureSlug.getText();
            if (slug != null && !slug.isBlank()) {
                onStart.accept(pipeline, slug.trim());
            }
        });
        content.getChildren().add(start);
    }

    private Label errorLabel(String text) {
        Label label = new Label(text);
        label.setTextFill(Color.web("#d93025"));
        label.setWrapText(true);
        return label;
    }
}
