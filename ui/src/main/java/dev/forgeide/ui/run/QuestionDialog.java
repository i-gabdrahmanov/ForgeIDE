package dev.forgeide.ui.run;

import dev.forgeide.core.event.EngineEvent;
import dev.forgeide.core.run.PendingQuestion;
import dev.forgeide.core.run.QuestionType;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The T13 {@code pending_questions} dialog (SDD FR-10.3): the same non-modal, dismiss-and-reopen
 * shape as {@link GateDialog} — a {@code radio} form for {@code choice}, a {@code textarea} for
 * {@code text}, with each question's {@code context} artifact rendered straight from disk right
 * next to it. Answering all questions posts one {@code QuestionsAnswered} for the whole round.
 */
public final class QuestionDialog {

    @FunctionalInterface
    public interface AnswerHandler {
        void onAnswer(Map<String, String> answers);
    }

    private QuestionDialog() {
    }

    public static Stage show(EngineEvent.QuestionsPending request, Path repoRoot,
                              AnswerHandler onAnswer, Runnable onDismiss) {
        Stage stage = new Stage();
        stage.initModality(Modality.NONE);
        stage.setTitle("Questions — " + request.stepId());

        VBox questionsBox = new VBox(16);
        Map<String, Supplier<Optional<String>>> readers = new LinkedHashMap<>();
        for (PendingQuestion question : request.questions()) {
            readers.put(question.id(), questionRow(question, repoRoot, questionsBox));
        }
        ScrollPane scroll = new ScrollPane(questionsBox);
        scroll.setFitToWidth(true);

        Label warning = new Label();
        warning.setStyle("-fx-text-fill: #d93025;");

        Button submit = new Button("Отправить ответы");
        submit.setOnAction(e -> {
            Map<String, String> answers = new LinkedHashMap<>();
            for (Map.Entry<String, Supplier<Optional<String>>> entry : readers.entrySet()) {
                Optional<String> value = entry.getValue().get();
                if (value.isEmpty() || value.get().isBlank()) {
                    warning.setText("Ответьте на все вопросы перед отправкой.");
                    return;
                }
                answers.put(entry.getKey(), value.get());
            }
            onAnswer.onAnswer(answers);
            stage.close();
        });
        Button dismiss = new Button("Закрыть (ответить позже)");
        dismiss.setOnAction(e -> {
            onDismiss.run();
            stage.close();
        });

        HBox buttons = new HBox(8, submit, dismiss, warning);
        buttons.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(10, scroll, new Separator(), buttons);
        root.setPadding(new Insets(12));
        VBox.setVgrow(scroll, Priority.ALWAYS);

        stage.setScene(new Scene(root, 760, 560));
        stage.setOnCloseRequest(e -> onDismiss.run());
        stage.show();
        return stage;
    }

    /** One question's form control (radio for {@code choice}, textarea for {@code text}) plus,
     * if it has a {@code context}, that artifact rendered read-only beside it (FR-10.1: "рядом
     * рендер context-артефакта"). Returns a reader for the currently entered/selected answer. */
    private static Supplier<Optional<String>> questionRow(PendingQuestion question, Path repoRoot, VBox container) {
        Label text = new Label(question.text());
        text.setWrapText(true);
        text.setStyle("-fx-font-weight: bold;");

        Node input;
        Supplier<Optional<String>> reader;
        if (question.type() == QuestionType.CHOICE) {
            ToggleGroup group = new ToggleGroup();
            VBox radios = new VBox(4);
            for (String option : question.options()) {
                RadioButton button = new RadioButton(option);
                button.setToggleGroup(group);
                button.setUserData(option);
                radios.getChildren().add(button);
            }
            input = radios;
            reader = () -> Optional.ofNullable(group.getSelectedToggle()).map(t -> (String) t.getUserData());
        } else {
            TextArea area = new TextArea();
            area.setPrefRowCount(3);
            input = area;
            reader = () -> Optional.ofNullable(area.getText());
        }

        VBox left = new VBox(6, text, input);
        left.setPrefWidth(340);
        HBox row = new HBox(12, left);
        question.context().ifPresent(context -> row.getChildren().add(contextPane(repoRoot, context)));
        container.getChildren().add(row);
        return reader;
    }

    private static Node contextPane(Path repoRoot, String context) {
        TextArea area = new TextArea();
        area.setEditable(false);
        area.setStyle("-fx-font-family: monospace;");
        Path resolved = repoRoot.resolve(context);
        try {
            area.setText(Files.isRegularFile(resolved)
                    ? Files.readString(resolved)
                    : "(context artifact not found: " + context + ")");
        } catch (IOException e) {
            area.setText("(could not read " + context + ": " + e.getMessage() + ")");
        }
        area.setPrefWidth(320);
        VBox box = new VBox(4, new Label(context), area);
        VBox.setVgrow(area, Priority.ALWAYS);
        return box;
    }
}
