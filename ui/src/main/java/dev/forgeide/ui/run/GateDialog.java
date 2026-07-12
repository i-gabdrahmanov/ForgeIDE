package dev.forgeide.ui.run;

import dev.forgeide.core.event.EngineEvent;
import dev.forgeide.core.run.EscalationAction;
import dev.forgeide.core.run.QuestionEscalationAction;
import dev.forgeide.runtime.git.GitDiffReader;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.Node;
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
 * The same shape serves a real {@code GateStep}, a judge's fail-policy-exhaustion escalation, and
 * a {@code pending_questions} round-limit escalation (FR-11.3/FR-10.5's "shared infrastructure"):
 * these arrive with more than two options and get either the {@code edit_prompt}/{@code override}
 * tokens' inline text prompts, or — T25 — the question-escalation's {@code open_prompt} link that
 * dismisses this dialog (leaving the step's status untouched, reopenable from the canvas exactly
 * like a plain dismiss) and opens the T20 prompt editor, plus a companion "retry with the new
 * prompt" button that composes the existing {@code cancel} answer with the existing manual-retry
 * command — no new engine command either way.
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

    /** Plain gate / judge-escalation overload — no {@code pending_questions} escalation options,
     * so there is nothing for {@code onOpenPromptEditor}/{@code onRetryWithNewPrompt} to ever
     * trigger. */
    public static Stage show(EngineEvent.GateRequest request, Path repoRoot,
                              AnswerHandler onAnswer, Runnable onDismiss) {
        return show(request, repoRoot, onAnswer, onDismiss, () -> { }, () -> { });
    }

    /**
     * @param onOpenPromptEditor T25/FR-10.5: fired when the human clicks the question-escalation's
     *                           {@code open_prompt} link — after this dialog has already dismissed
     *                           itself exactly like {@code onDismiss} (the step's status is left
     *                           untouched, the escalation stays reopenable from the canvas).
     * @param onRetryWithNewPrompt T25: fired by the companion "Повторить с новым промптом" button,
     *                              shown only alongside the question-escalation's options — the
     *                              caller composes the existing {@code cancel} gate answer with
     *                              the existing manual-retry command, so the prompt just saved via
     *                              the editor's FR-8.2 trusted path takes effect on the retry.
     */
    public static Stage show(EngineEvent.GateRequest request, Path repoRoot,
                              AnswerHandler onAnswer, Runnable onDismiss,
                              Runnable onOpenPromptEditor, Runnable onRetryWithNewPrompt) {
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
            buttons.getChildren().add(optionControl(option, requiresAck, diffAck, onAnswer, onDismiss, stage,
                    onOpenPromptEditor));
        }
        if (request.options().contains(QuestionEscalationAction.OPEN_PROMPT.token())) {
            buttons.getChildren().add(retryWithNewPromptButton(onRetryWithNewPrompt, stage));
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
                                       AnswerHandler onAnswer, Runnable onDismiss, Stage stage,
                                       Runnable onOpenPromptEditor) {
        if (option.equals(EscalationAction.EDIT_PROMPT.token())) {
            return inlineTextControl(option, "Replacement prompt text…", true, requiresAck, diffAck, onAnswer, stage);
        }
        if (option.equals(EscalationAction.OVERRIDE.token())) {
            return inlineTextControl(option, "Override reason (обязательна)…", false, requiresAck, diffAck, onAnswer, stage);
        }
        if (option.equals(QuestionEscalationAction.OPEN_PROMPT.token())) {
            // T25/FR-10.5: never answers the engine — same semantics as a deferred gate answer
            // (the step's status is left untouched, the escalation reopens from the canvas), just
            // followed by a local navigation to the T20 prompt editor for the escalated step.
            Button open = new Button(label(option));
            open.setOnAction(e -> {
                onDismiss.run();
                stage.close();
                onOpenPromptEditor.run();
            });
            return open;
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

    /** T25: composes the existing {@code cancel} gate answer (ends this escalated attempt as
     * {@code FAILED(questions)}) with the existing manual-retry command — the same pair a human
     * could already trigger by hand across two separate screens, just reachable in one click from
     * the escalation dialog itself once the prompt has been edited via the T20 editor's FR-8.2
     * trusted path. No new engine command. */
    private static Button retryWithNewPromptButton(Runnable onRetryWithNewPrompt, Stage stage) {
        Button retry = new Button("Повторить с новым промптом");
        retry.setOnAction(e -> {
            stage.close();
            onRetryWithNewPrompt.run();
        });
        return retry;
    }

    private static String label(String token) {
        return switch (token) {
            case "retry" -> "Повторить";
            case "edit_prompt" -> "Править промпт";
            case "reset_chain" -> "Сбросить цепочку";
            case "cancel" -> "Отмена";
            case "override" -> "Override";
            case "open_prompt" -> "Открыть промпт в редакторе";
            case "split_step" -> "Разбить шаг";
            default -> token;
        };
    }
}
