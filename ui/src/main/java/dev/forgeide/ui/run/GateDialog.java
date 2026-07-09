package dev.forgeide.ui.run;

import dev.forgeide.core.event.EngineEvent;
import dev.forgeide.core.run.EscalationAction;
import dev.forgeide.core.run.QuestionEscalationAction;
import dev.forgeide.runtime.git.GitDiffReader;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * The T12 gate dialog (SDD FR-5, FR-11.3): a non-modal window so it can be dismissed and the
 * step left {@code WAITING_GATE} (reopened later from the canvas tile — see {@code RunView}),
 * showing only real data read fresh from disk at open time — never the model's own word (FR-5.2).
 * The same shape serves a real {@code GateStep} and a judge's fail-policy-exhaustion escalation
 * (FR-11.3's "shared infrastructure"): the latter arrives with more than two options and gets the
 * {@code edit_prompt}/{@code override} tokens' inline text prompts instead of a plain button.
 */
public final class GateDialog {

    /** {@code answer}, {@code detail} (edited prompt/override reason, if any), {@code diffAcked}
     * (the FR-5.3 checkbox state at the moment of confirmation). */
    @FunctionalInterface
    public interface AnswerHandler {
        void onAnswer(String answer, Optional<String> detail, boolean diffAcked);
    }

    private GateDialog() {
    }

    public static Stage show(EngineEvent.GateRequest request, Path repoRoot,
                              AnswerHandler onAnswer, Runnable onDismiss) {
        Stage stage = new Stage();
        stage.initModality(Modality.NONE);
        stage.setTitle("Gate — " + request.stepId());

        Label question = new Label(request.question());
        question.setWrapText(true);
        question.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

        Label untrustedBanner = untrustedNote(
                "Any AI-authored text is UNTRUSTED — the tabs below are read directly from disk; "
                        + "verify against them, not against any model summary.");

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().add(diffTab(repoRoot));
        for (Path artifact : request.artifacts()) {
            artifactTab(artifact).ifPresent(tabs.getTabs()::add);
        }
        if (!request.errorsHistory().isEmpty()) {
            tabs.getTabs().add(historyTab(request.errorsHistory()));
        }

        CheckBox diffAck = new CheckBox("Я посмотрел diff");
        boolean requiresAck = GateAckPolicy.requiresDiffAck(request.risk());
        diffAck.setVisible(requiresAck);
        diffAck.setManaged(requiresAck);

        HBox buttons = new HBox(8);
        for (String option : request.options()) {
            buttons.getChildren().add(optionControl(option, requiresAck, diffAck, onAnswer, stage));
        }
        Button dismiss = new Button("Закрыть (решить позже)");
        dismiss.setOnAction(e -> {
            onDismiss.run();
            stage.close();
        });
        buttons.getChildren().add(dismiss);

        VBox root = new VBox(10, question, untrustedBanner, tabs, diffAck, buttons);
        root.setPadding(new Insets(12));
        VBox.setVgrow(tabs, Priority.ALWAYS);

        stage.setScene(new Scene(root, 760, 560));
        stage.setOnCloseRequest(e -> onDismiss.run());
        stage.show();
        return stage;
    }

    private static Tab diffTab(Path repoRoot) {
        TextArea area = new TextArea("Loading git diff…");
        area.setEditable(false);
        area.setStyle("-fx-font-family: monospace;");
        Thread.ofVirtual().start(() -> {
            String diff = GitDiffReader.read(repoRoot, Duration.ofSeconds(10));
            Platform.runLater(() -> area.setText(diff));
        });
        Tab tab = new Tab("git diff", area);
        tab.setClosable(false);
        return tab;
    }

    private static Optional<Tab> artifactTab(Path artifact) {
        if (!Files.isRegularFile(artifact)) {
            return Optional.empty();
        }
        TextArea area = new TextArea();
        area.setEditable(false);
        area.setStyle("-fx-font-family: monospace;");
        try {
            area.setText(Files.readString(artifact));
        } catch (IOException e) {
            area.setText("(could not read " + artifact + ": " + e.getMessage() + ")");
        }
        Tab tab = new Tab(artifact.getFileName().toString(), area);
        tab.setClosable(false);
        return Optional.of(tab);
    }

    private static Tab historyTab(List<String> errorsHistory) {
        TextArea area = new TextArea();
        area.setEditable(false);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < errorsHistory.size(); i++) {
            sb.append("iteration ").append(i + 1).append(":\n").append(errorsHistory.get(i)).append("\n\n");
        }
        area.setText(sb.toString());
        VBox box = new VBox(4, untrustedNote("Judge/LLM failure detail per iteration — UNTRUSTED, cross-check "
                + "against the diff/artifact tabs."), area);
        VBox.setVgrow(area, Priority.ALWAYS);
        Tab tab = new Tab("History", box);
        tab.setClosable(false);
        return tab;
    }

    private static Label untrustedNote(String text) {
        Label label = new Label("⚠ " + text);
        label.setWrapText(true);
        label.setStyle("-fx-background-color: #fff3cd; -fx-text-fill: #7a5b00; -fx-padding: 6;");
        return label;
    }

    private static Node optionControl(String option, boolean requiresAck, CheckBox diffAck,
                                       AnswerHandler onAnswer, Stage stage) {
        if (option.equals(EscalationAction.EDIT_PROMPT.token())) {
            return inlineTextControl(option, "Replacement prompt text…", true, requiresAck, diffAck, onAnswer, stage);
        }
        if (option.equals(EscalationAction.OVERRIDE.token())) {
            return inlineTextControl(option, "Override reason (обязательна)…", false, requiresAck, diffAck, onAnswer, stage);
        }
        if (option.equals(QuestionEscalationAction.OPEN_PROMPT.token())) {
            // FR-10.5 out-of-scope stub (T20 doesn't exist yet): a link that never answers the
            // engine — the dialog stays open so the human can still pick split_step/cancel.
            Button stub = new Button(label(option));
            stub.setOnAction(e -> openPromptStubAlert());
            return stub;
        }
        Button button = new Button(label(option));
        if (requiresAck) {
            button.disableProperty().bind(diffAck.selectedProperty().not());
        }
        button.setOnAction(e -> {
            onAnswer.onAnswer(option, Optional.empty(), diffAck.isSelected());
            stage.close();
        });
        return button;
    }

    /** {@code edit_prompt}/{@code override} both need mandatory free text before they can be
     * submitted (FR-11.3) — a reveal button that expands into a text input plus its own confirm,
     * disabled until non-blank. {@code multiline} picks a {@link TextArea} vs a {@link TextField}. */
    private static Node inlineTextControl(String token, String prompt, boolean multiline, boolean requiresAck,
                                           CheckBox diffAck, AnswerHandler onAnswer, Stage stage) {
        TextInputControl input = multiline ? new TextArea() : new TextField();
        input.setPromptText(prompt);
        if (input instanceof TextArea area) {
            area.setPrefRowCount(3);
        }
        input.setVisible(false);
        input.setManaged(false);

        Button submit = new Button(multiline ? "Отправить" : "Подтвердить");
        submit.setVisible(false);
        submit.setManaged(false);
        submit.setDisable(true);
        input.textProperty().addListener((obs, old, val) -> submit.setDisable(val == null || val.isBlank()));

        Button reveal = new Button(label(token));
        if (requiresAck) {
            reveal.disableProperty().bind(diffAck.selectedProperty().not());
        }
        reveal.setOnAction(e -> {
            boolean show = !input.isVisible();
            input.setVisible(show);
            input.setManaged(show);
            submit.setVisible(show);
            submit.setManaged(show);
        });
        submit.setOnAction(e -> {
            onAnswer.onAnswer(token, Optional.of(input.getText()), diffAck.isSelected());
            stage.close();
        });
        return new VBox(4, reveal, input, submit);
    }

    private static void openPromptStubAlert() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION,
                "Промпт-редактор ещё не реализован (T20). Отредактируйте файл промпта вручную "
                        + "и выберите «Разбить шаг» или «Отмена», либо повторите шаг позже.");
        alert.setHeaderText("Открыть промпт в редакторе");
        alert.showAndWait();
    }

    private static String label(String token) {
        return switch (token) {
            case "retry" -> "Повторить";
            case "edit_prompt" -> "Править промпт";
            case "reset_chain" -> "Сбросить цепочку";
            case "cancel" -> "Отмена";
            case "override" -> "Override";
            case "open_prompt" -> "Открыть промпт в редакторе (заглушка)";
            case "split_step" -> "Разбить шаг";
            default -> token;
        };
    }
}
