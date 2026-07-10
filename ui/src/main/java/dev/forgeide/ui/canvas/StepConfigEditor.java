package dev.forgeide.ui.canvas;

import dev.forgeide.core.pipeline.AgentStep;
import dev.forgeide.core.pipeline.BranchStep;
import dev.forgeide.core.pipeline.GateStep;
import dev.forgeide.core.pipeline.JudgeStep;
import dev.forgeide.core.pipeline.OutwardAction;
import dev.forgeide.core.pipeline.OutwardStep;
import dev.forgeide.core.pipeline.PerTaskLoop;
import dev.forgeide.core.pipeline.ScriptStep;
import dev.forgeide.core.pipeline.StepDefinition;
import dev.forgeide.core.pipeline.TileValidity;
import dev.forgeide.core.pipeline.validation.PipelineError;
import dev.forgeide.core.pipeline.yaml.Durations;
import dev.forgeide.core.policy.FailPolicy;
import dev.forgeide.core.policy.RetryPolicy;
import dev.forgeide.core.policy.TokenBudget;
import dev.forgeide.core.project.RiskLevel;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The T22 constructor's editable "Config" tab (FR-2.5: "Редактирование конфига шага в
 * инспекторе... формы по типу"). Supersedes the read-only field list ({@link StepDetailFields}
 * via {@link TileDetailPanel}) for the idle canvas: one form per step type, an "Apply" button
 * that builds the replacement {@link StepDefinition} and hands it to the caller (which routes
 * it through {@code PipelineEdits.replaceStep} — this class never touches a {@link
 * dev.forgeide.core.pipeline.edit.PipelineDocument} directly, same separation of concerns as
 * {@code TileEditorPanel}'s save handlers).
 *
 * <p>{@link PerTaskLoop}'s nested {@code template} is shown read-only — editing a loop's inner
 * subgraph is a separate concern from editing the loop tile's own fields (its id isn't even a
 * top-level entry the canvas renders as its own tile), so this form only lets you retarget
 * {@code task_plan}; use the YAML tab for the template itself.
 */
public final class StepConfigEditor extends VBox {

    @FunctionalInterface
    public interface OnApply {
        void apply(StepDefinition replacement);
    }

    private final VBox content = new VBox(8);

    public StepConfigEditor() {
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        content.setPadding(new Insets(12));
        getChildren().add(scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        showEmpty();
    }

    public void showEmpty() {
        content.getChildren().setAll(new Label("Select a tile to edit its config."));
    }

    public void show(StepDefinition step, List<PipelineError> errors, TileValidity validity, OnApply onApply) {
        content.getChildren().clear();
        content.getChildren().addAll(TileDetailPanel.header(step, errors, validity));
        content.getChildren().add(new Separator());
        content.getChildren().addAll(switch (step) {
            case AgentStep a -> agentForm(a, onApply);
            case ScriptStep s -> scriptForm(s, onApply);
            case JudgeStep j -> judgeForm(j, onApply);
            case GateStep g -> gateForm(g, onApply);
            case BranchStep b -> branchForm(b, onApply);
            case PerTaskLoop l -> perTaskLoopForm(l, onApply);
            case OutwardStep o -> outwardForm(o, onApply);
        });
    }

    // ---- per-type forms ---------------------------------------------------------------------

    private List<Node> agentForm(AgentStep a, OnApply onApply) {
        TextField runtime = textField(a.runtimeRef());
        TextField prompt = textField(a.promptTemplate().toString());
        TextArea expects = linesArea(a.expectedArtifacts().stream().map(Path::toString).toList());
        TextArea allowedWrite = linesArea(a.allowedWrite());
        TextArea envScope = linesArea(a.envScope());
        TextField tokens = textField(Long.toString(a.budget().tokens()));
        TextField wallClock = textField(Durations.format(a.budget().wallClock()));
        TextField outputMb = textField(Long.toString(a.budget().outputMb()));
        TextField retryStream = textField(Integer.toString(a.retry().stream()));
        TextField retryScript = textField(Integer.toString(a.retry().script()));

        Supplier<StepDefinition> build = () -> new AgentStep(a.id(), a.dependsOn(), runtime.getText().strip(),
                Path.of(prompt.getText().strip()), pathsOf(expects), linesOf(allowedWrite), linesOf(envScope),
                new RetryPolicy(parseInt(retryStream), parseInt(retryScript)),
                new TokenBudget(parseLong(tokens), Durations.parse(wallClock.getText().strip()), parseLong(outputMb)));

        return List.of(
                labeled("runtime", runtime), labeled("prompt", prompt),
                labeled("expects (one path per line)", expects),
                labeled("allowed_write (one glob per line)", allowedWrite),
                labeled("env_scope (one key per line)", envScope),
                labeled("budget.tokens", tokens), labeled("budget.wall_clock", wallClock),
                labeled("budget.output_mb", outputMb),
                labeled("retry.stream", retryStream), labeled("retry.script", retryScript),
                applyButton(build, onApply));
    }

    private List<Node> scriptForm(ScriptStep s, OnApply onApply) {
        TextArea command = linesArea(s.command());
        TextField timeout = textField(Durations.format(s.timeout()));
        TextField retryStream = textField(Integer.toString(s.retry().stream()));
        TextField retryScript = textField(Integer.toString(s.retry().script()));

        Supplier<StepDefinition> build = () -> new ScriptStep(s.id(), s.dependsOn(), linesOf(command),
                Durations.parse(timeout.getText().strip()),
                new RetryPolicy(parseInt(retryStream), parseInt(retryScript)));

        return List.of(
                labeled("command (one argument per line)", command),
                labeled("timeout", timeout),
                labeled("retry.stream", retryStream), labeled("retry.script", retryScript),
                applyButton(build, onApply));
    }

    private List<Node> judgeForm(JudgeStep j, OnApply onApply) {
        TextField target = textField(j.targetStepId());
        TextField maxIterations = textField(Integer.toString(j.failPolicy().maxIterations()));

        CheckBox hasCheck = new CheckBox("Deterministic check");
        hasCheck.setSelected(j.deterministicCheck().isPresent());
        TextArea checkCommand = linesArea(j.deterministicCheck().map(ScriptStep::command).orElse(List.of()));
        TextField checkTimeout = textField(Durations.format(
                j.deterministicCheck().map(ScriptStep::timeout).orElse(java.time.Duration.ofMinutes(5))));

        CheckBox hasLlm = new CheckBox("LLM judge");
        hasLlm.setSelected(j.llmJudge().isPresent());
        TextField llmRuntime = textField(j.llmJudge().map(AgentStep::runtimeRef).orElse("claude"));
        TextField llmPrompt = textField(j.llmJudge().map(a -> a.promptTemplate().toString()).orElse(""));

        Supplier<StepDefinition> build = () -> {
            Optional<ScriptStep> check = hasCheck.isSelected()
                    ? Optional.of(new ScriptStep(j.id() + ".check", List.of(), linesOf(checkCommand),
                            Durations.parse(checkTimeout.getText().strip())))
                    : Optional.empty();
            Optional<AgentStep> llm = hasLlm.isSelected()
                    ? Optional.of(new AgentStep(j.id() + ".llm", List.of(), llmRuntime.getText().strip(),
                            Path.of(llmPrompt.getText().strip()), List.of(), List.of(), List.of(),
                            RetryPolicy.DEFAULT, TokenBudget.DEFAULT))
                    : Optional.empty();
            return new JudgeStep(j.id(), j.dependsOn(), target.getText().strip(), llm, check,
                    new FailPolicy(parseInt(maxIterations)));
        };

        return List.of(
                labeled("target", target), labeled("fail_policy.max_iterations", maxIterations),
                hasCheck, labeled("check.command (one argument per line)", checkCommand),
                labeled("check.timeout", checkTimeout),
                hasLlm, labeled("llm.runtime", llmRuntime), labeled("llm.prompt", llmPrompt),
                applyButton(build, onApply));
    }

    private List<Node> gateForm(GateStep g, OnApply onApply) {
        TextField question = textField(g.question());
        TextArea options = linesArea(g.options());
        TextArea show = linesArea(g.showArtifacts().stream().map(Path::toString).toList());
        ComboBox<RiskLevel> risk = new ComboBox<>();
        risk.getItems().addAll(RiskLevel.values());
        risk.setValue(g.risk());

        Supplier<StepDefinition> build = () -> new GateStep(g.id(), g.dependsOn(), question.getText().strip(),
                linesOf(options), pathsOf(show), risk.getValue());

        return List.of(
                labeled("question", question), labeled("options (one per line)", options),
                labeled("show (one path per line)", show), labeled("risk", risk),
                applyButton(build, onApply));
    }

    private List<Node> branchForm(BranchStep b, OnApply onApply) {
        String initial = b.routes().entrySet().stream()
                .map(e -> e.getKey() + " -> " + e.getValue())
                .collect(Collectors.joining("\n"));
        TextArea routes = new TextArea(initial);
        routes.setPrefRowCount(6);

        Supplier<StepDefinition> build = () -> new BranchStep(b.id(), b.dependsOn(), parseRoutes(routes));

        return List.of(labeled("routes (one 'answer -> target_id' per line)", routes), applyButton(build, onApply));
    }

    private List<Node> perTaskLoopForm(PerTaskLoop l, OnApply onApply) {
        TextField taskPlan = textField(l.taskPlanJson().toString());
        Label templateInfo = new Label("template: " + l.template().stream()
                .map(StepDefinition::id).collect(Collectors.joining(", "))
                + " — edit the nested steps via the YAML tab");
        templateInfo.setWrapText(true);

        Supplier<StepDefinition> build = () -> new PerTaskLoop(l.id(), l.dependsOn(),
                Path.of(taskPlan.getText().strip()), l.template());

        return List.of(labeled("task_plan", taskPlan), templateInfo, applyButton(build, onApply));
    }

    private List<Node> outwardForm(OutwardStep o, OnApply onApply) {
        Map<OutwardAction, CheckBox> actionBoxes = new LinkedHashMap<>();
        List<Node> actionNodes = new ArrayList<>();
        actionNodes.add(new Label("actions"));
        for (OutwardAction action : OutwardAction.values()) {
            CheckBox box = new CheckBox(action.name());
            box.setSelected(o.actions().contains(action));
            actionBoxes.put(action, box);
            actionNodes.add(box);
        }
        TextArea envScope = linesArea(o.envScope());
        TextField retryStream = textField(Integer.toString(o.retry().stream()));
        TextField retryScript = textField(Integer.toString(o.retry().script()));

        Supplier<StepDefinition> build = () -> {
            List<OutwardAction> actions = actionBoxes.entrySet().stream()
                    .filter(e -> e.getValue().isSelected())
                    .map(Map.Entry::getKey)
                    .toList();
            return new OutwardStep(o.id(), o.dependsOn(), actions, linesOf(envScope),
                    new RetryPolicy(parseInt(retryStream), parseInt(retryScript)));
        };

        List<Node> nodes = new ArrayList<>(actionNodes);
        nodes.add(labeled("env_scope (one key per line)", envScope));
        nodes.add(labeled("retry.stream", retryStream));
        nodes.add(labeled("retry.script", retryScript));
        nodes.add(applyButton(build, onApply));
        return nodes;
    }

    // ---- shared field/parsing helpers ---------------------------------------------------------

    private static VBox labeled(String label, Node control) {
        Label l = new Label(label);
        l.setStyle("-fx-font-weight: bold; -fx-font-size: 11px; -fx-text-fill: #555555;");
        return new VBox(2, l, control);
    }

    private static TextField textField(String value) {
        return new TextField(value);
    }

    private static TextArea linesArea(List<String> values) {
        TextArea area = new TextArea(String.join("\n", values));
        area.setPrefRowCount(Math.max(2, Math.min(6, values.size() + 1)));
        return area;
    }

    private static List<String> linesOf(TextArea area) {
        return area.getText().lines().map(String::strip).filter(s -> !s.isBlank()).toList();
    }

    private static List<Path> pathsOf(TextArea area) {
        return linesOf(area).stream().map(Path::of).toList();
    }

    private static Map<String, String> parseRoutes(TextArea area) {
        Map<String, String> routes = new LinkedHashMap<>();
        for (String line : linesOf(area)) {
            String[] parts = line.split("->", 2);
            if (parts.length == 2) {
                routes.put(parts[0].strip(), parts[1].strip());
            }
        }
        return routes;
    }

    private static int parseInt(TextField field) {
        return Integer.parseInt(field.getText().strip());
    }

    private static long parseLong(TextField field) {
        return Long.parseLong(field.getText().strip());
    }

    private static Button applyButton(Supplier<StepDefinition> build, OnApply onApply) {
        Button apply = new Button("Apply");
        apply.setOnAction(e -> {
            try {
                onApply.apply(build.get());
            } catch (RuntimeException ex) {
                new Alert(Alert.AlertType.ERROR, "Could not apply: " + ex.getMessage()).showAndWait();
            }
        });
        return apply;
    }
}
