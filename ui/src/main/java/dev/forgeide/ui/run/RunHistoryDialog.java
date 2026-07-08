package dev.forgeide.ui.run;

import dev.forgeide.core.port.StateStore;
import dev.forgeide.core.project.ProjectDefinition;
import dev.forgeide.core.run.RunSnapshot;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Entry point into a feature's run history independent of starting a new run (T11: "предложение
 * ретрая в UI" needs somewhere to find a run FR-3.4 recovery left with an interrupted step, since
 * {@code StartRunDialog} only ever launches a fresh one). A feature slug plus a "View" button,
 * same shape as {@code StartRunDialog}.
 */
public final class RunHistoryDialog extends BorderPane {

    public RunHistoryDialog(ProjectDefinition project, StateStore stateStore, Runnable onBack,
                             Consumer<RunSnapshot> onResume) {
        Button back = new Button("← Back");
        back.setOnAction(e -> onBack.run());
        Label title = new Label("Run history — " + project.name());
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        HBox header = new HBox(12, back, title);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(12));
        setTop(header);

        TextField featureSlug = new TextField();
        featureSlug.setPromptText("feature-slug (e.g. a Jira ticket)");
        Button view = new Button("View");

        VBox form = new VBox(12, new Label("Feature slug"), new HBox(8, featureSlug, view));
        form.setPadding(new Insets(16));
        setCenter(form);

        view.setOnAction(e -> {
            String slug = featureSlug.getText();
            if (slug == null || slug.isBlank()) {
                return;
            }
            setCenter(new RunListView(stateStore, slug.trim(), Optional.of(onResume)));
        });
    }
}
