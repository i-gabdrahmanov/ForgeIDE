package dev.forgeide.ui.editor;

import dev.forgeide.core.pipeline.AgentStep;
import dev.forgeide.core.pipeline.JudgeStep;
import dev.forgeide.core.pipeline.ScriptStep;
import dev.forgeide.core.pipeline.StepDefinition;
import dev.forgeide.core.pipeline.TileValidity;
import dev.forgeide.core.pipeline.validation.PipelineError;
import dev.forgeide.runtime.git.GitDiffReader;
import dev.forgeide.runtime.harness.JudgeScriptLocator;
import dev.forgeide.ui.canvas.StepConfigEditor;
import dev.forgeide.ui.canvas.TileDetailPanel;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.fxmisc.flowless.VirtualizedScrollPane;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * T20/FR-8.1-8.3 editable tile inspector — supersedes the read-only {@link TileDetailPanel} as
 * the canvas selection's detail view. Tabs: Prompt (agent/llm-judge steps), Script (script/judge
 * steps, marked "trusted path" when the resolved file lives under the project's harness), and
 * Config (the original read-only {@link TileDetailPanel}, reused as-is — a step's structured
 * config is T22 "constructor" territory to make editable, not this task).
 *
 * <p>Save routing is injected rather than hard-wired: a {@code null} handler (or a {@code null}
 * {@code projectRoot}) means read-only (e.g. the bundled forgelite template preview, which has no
 * project to write into) — the caller decides whether a save writes straight to disk (idle
 * design-time canvas) or goes through a live {@code PipelineEngine} command (mid-run, FR-8.2's
 * "next step run only" + audit trail).
 *
 * <p>T21/FR-8.4-8.5 add two more optional, independently-nullable handlers on the same idle/live
 * split: {@link #promptPreviewHandler} (render preview, Prompt tab) and {@link
 * #judgeDryRunHandler} ("прогнать судью", Script tab, only for a {@code JudgeStep}). Both are
 * {@code null} on the idle design-time canvas — there is no live {@code PipelineEngine} there to
 * render {@code accumulated_errors}/{@code answers} against or to execute a check script through
 * (same "nothing is audited" reasoning {@link dev.forgeide.ui.canvas.PipelineCanvasView}'s own
 * javadoc gives for why saves there skip the engine entirely).
 */
public final class TileEditorPanel extends TabPane {

    @FunctionalInterface
    public interface PromptSaveHandler {
        /** @param stepId the {@code AgentStep}/{@code JudgeStep} id (engine resolves the actual
         *                template/path itself when routed live — see {@code EngineCommand.PromptEdited}). */
        void save(String stepId, Path absolutePromptPath, String newContent);
    }

    @FunctionalInterface
    public interface ScriptSaveHandler {
        void save(Path relativeScriptPath, String newContent);
    }

    /** T21/FR-8.5: async — the render happens on the live engine's actor thread, the result comes
     * back on the FX thread (mirrors how {@code RunViewModel} already bridges every other engine
     * reply). */
    @FunctionalInterface
    public interface PromptPreviewHandler {
        /** @param stepId the {@code AgentStep}/{@code JudgeStep} id, same resolution rule as
         *                {@link PromptSaveHandler#save}. */
        void preview(String stepId, Consumer<String> onRendered);
    }

    /** T21/FR-8.4: async, same FX-thread-delivery contract as {@link PromptPreviewHandler}. */
    @FunctionalInterface
    public interface JudgeDryRunHandler {
        void dryRun(String judgeStepId, Consumer<JudgeDryRunOutcome> onResult);
    }

    /** The verdict a dry-run click produced — never persisted, purely for display (FR-8.4). */
    public record JudgeDryRunOutcome(boolean passed, String detail) {
    }

    private static final Duration GIT_DIFF_TIMEOUT = Duration.ofSeconds(5);

    private final Path projectRoot;
    private final PromptSaveHandler promptSaveHandler;
    private final ScriptSaveHandler scriptSaveHandler;
    private final PromptPreviewHandler promptPreviewHandler;
    private final JudgeDryRunHandler judgeDryRunHandler;
    private final StepConfigEditor.OnApply configSaveHandler;
    private final TileDetailPanel configPanel = new TileDetailPanel();
    private final StepConfigEditor configEditor = new StepConfigEditor();

    public TileEditorPanel(Path projectRoot, PromptSaveHandler promptSaveHandler, ScriptSaveHandler scriptSaveHandler) {
        this(projectRoot, promptSaveHandler, scriptSaveHandler, null, null, null);
    }

    public TileEditorPanel(Path projectRoot, PromptSaveHandler promptSaveHandler, ScriptSaveHandler scriptSaveHandler,
                            PromptPreviewHandler promptPreviewHandler, JudgeDryRunHandler judgeDryRunHandler) {
        this(projectRoot, promptSaveHandler, scriptSaveHandler, promptPreviewHandler, judgeDryRunHandler, null);
    }

    /** T22/FR-2.5: {@code configSaveHandler} swaps the read-only Config tab for an editable
     * {@link StepConfigEditor}; {@code null} (every other overload) keeps it read-only exactly
     * as before — the mid-run {@code RunView} inspector never passes one. */
    public TileEditorPanel(Path projectRoot, PromptSaveHandler promptSaveHandler, ScriptSaveHandler scriptSaveHandler,
                            PromptPreviewHandler promptPreviewHandler, JudgeDryRunHandler judgeDryRunHandler,
                            StepConfigEditor.OnApply configSaveHandler) {
        this.projectRoot = projectRoot;
        this.promptSaveHandler = promptSaveHandler;
        this.scriptSaveHandler = scriptSaveHandler;
        this.promptPreviewHandler = promptPreviewHandler;
        this.judgeDryRunHandler = judgeDryRunHandler;
        this.configSaveHandler = configSaveHandler;
        setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        showEmpty();
    }

    public void showEmpty() {
        configPanel.showEmpty();
        configEditor.showEmpty();
        getTabs().setAll(new Tab("Config", configSaveHandler != null ? configEditor : configPanel));
    }

    public void show(StepDefinition step, List<PipelineError> errors, TileValidity validity) {
        Node configNode;
        if (configSaveHandler != null) {
            configEditor.show(step, errors, validity, configSaveHandler);
            configNode = configEditor;
        } else {
            configPanel.show(step, errors, validity);
            configNode = configPanel;
        }

        List<Tab> tabs = new ArrayList<>();
        promptTarget(step).ifPresent(target ->
                tabs.add(nonClosable("Prompt", promptEditor(target.stepId(), target.relativePath()))));
        scriptTarget(step).ifPresent(relativePath ->
                tabs.add(nonClosable("Script", scriptEditor(step, relativePath))));
        tabs.add(nonClosable("Config", configNode));
        getTabs().setAll(tabs);
    }

    private static Tab nonClosable(String title, Node content) {
        Tab tab = new Tab(title, content);
        tab.setClosable(false);
        return tab;
    }

    private record PromptTarget(String stepId, Path relativePath) {
    }

    private static Optional<PromptTarget> promptTarget(StepDefinition step) {
        if (step instanceof AgentStep agent) {
            return Optional.of(new PromptTarget(agent.id(), agent.promptTemplate()));
        }
        if (step instanceof JudgeStep judge && judge.llmJudge().isPresent()) {
            return Optional.of(new PromptTarget(judge.id(), judge.llmJudge().get().promptTemplate()));
        }
        return Optional.empty();
    }

    private Optional<Path> scriptTarget(StepDefinition step) {
        if (projectRoot == null) {
            return Optional.empty();
        }
        List<String> command = null;
        if (step instanceof ScriptStep script) {
            command = script.command();
        } else if (step instanceof JudgeStep judge && judge.deterministicCheck().isPresent()) {
            command = judge.deterministicCheck().get().command();
        }
        return command == null ? Optional.empty() : JudgeScriptLocator.locate(projectRoot, command);
    }

    // ---- Prompt tab ------------------------------------------------------------------------

    private Node promptEditor(String stepId, Path relativePath) {
        Path absolute = projectRoot == null ? null : projectRoot.resolve(relativePath);
        String original = readOrEmpty(absolute);

        PromptCodeArea area = new PromptCodeArea(PromptCodeArea.Language.MARKDOWN);
        area.replaceText(original);
        area.setContractBlock(ContractBlockLocator.locate(original).orElse(null));
        area.textProperty().addListener((obs, oldText, newText) ->
                area.setContractBlock(ContractBlockLocator.locate(newText).orElse(null)));

        Label status = new Label();
        TextArea diffArea = diffArea();
        TitledPane diffPane = collapsedDiffPane(diffArea);
        TextArea previewArea = diffArea();
        TitledPane previewPane = new TitledPane("Prompt preview (next run)", previewArea);
        previewPane.setExpanded(false);

        Button diffButton = new Button("Diff with git HEAD");
        diffButton.setDisable(projectRoot == null);
        diffButton.setOnAction(e -> {
            diffPane.setExpanded(true);
            loadDiffAsync(relativePath, diffArea);
        });

        // T21/FR-8.5: renders through the live engine's own dispatch-render code path (see
        // PipelineEngine#renderPromptPreview) — never a local reimplementation of ${...}/
        // accumulated_errors/answers substitution, so this can never show something a real next
        // run wouldn't actually send.
        Button previewButton = new Button("Preview render");
        previewButton.setDisable(promptPreviewHandler == null);
        previewButton.setOnAction(e -> {
            previewPane.setExpanded(true);
            previewArea.setText("Rendering…");
            promptPreviewHandler.preview(stepId, rendered -> previewArea.setText(rendered));
        });

        Button save = new Button("Save");
        save.setDisable(promptSaveHandler == null || projectRoot == null);
        save.setOnAction(e -> {
            String newContent = area.getText();
            if (!ContractBlockLocator.contractSurvives(original, newContent) && !confirmContractRemoval()) {
                return;
            }
            promptSaveHandler.save(stepId, absolute, newContent);
            status.setText("Saved at " + Instant.now());
        });

        HBox toolbar = new HBox(8, save, diffButton, previewButton, status);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(6));

        VirtualizedScrollPane<PromptCodeArea> scrollPane = new VirtualizedScrollPane<>(area);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        VBox box = new VBox(toolbar, scrollPane, diffPane, previewPane);
        return box;
    }

    // ---- Script tab ------------------------------------------------------------------------

    private Node scriptEditor(StepDefinition step, Path relativePath) {
        Path absolute = projectRoot.resolve(relativePath);
        String original = readOrEmpty(absolute);
        boolean trustedPath = HarnessPaths.isUnderHarness(relativePath);

        PromptCodeArea area = new PromptCodeArea(PromptCodeArea.Language.PYTHON);
        area.replaceText(original);

        Label status = new Label();
        TextArea diffArea = diffArea();
        TitledPane diffPane = collapsedDiffPane(diffArea);

        Button diffButton = new Button("Diff with git HEAD");
        diffButton.setOnAction(e -> {
            diffPane.setExpanded(true);
            loadDiffAsync(relativePath, diffArea);
        });

        Button save = new Button("Save");
        save.setDisable(scriptSaveHandler == null);
        save.setOnAction(e -> {
            scriptSaveHandler.save(relativePath, area.getText());
            status.setText("Saved at " + Instant.now());
        });

        HBox toolbar = new HBox(8, save, diffButton, status);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(6));
        if (trustedPath) {
            Label badge = new Label("Trusted path — save updates the harness cache + hash-manifest");
            badge.setStyle("-fx-text-fill: #1a73e8; -fx-font-size: 11px;");
            toolbar.getChildren().add(badge);
        }

        VirtualizedScrollPane<PromptCodeArea> scrollPane = new VirtualizedScrollPane<>(area);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        VBox box = new VBox(toolbar, scrollPane, diffPane);
        if (step instanceof JudgeStep judge) {
            box.getChildren().add(judgeDryRunBar(judge));
        }
        return box;
    }

    // ---- T21/FR-8.4 judge dry-run ------------------------------------------------------------

    /**
     * "Прогнать судью": runs the judge's check script (and its LLM verdict, if configured)
     * against the target step's artifacts as they sit on disk right now, through the same
     * {@code judge.dryrun} engine path a real judge dispatch's checks use — never touches the
     * run's status/SoT (FR-8.4). Save the script edit above first if the point is to test a fix;
     * this always re-resolves the check command fresh, so it immediately reflects a T20
     * trusted-path save without needing to restart anything.
     */
    private Node judgeDryRunBar(JudgeStep judge) {
        Label verdict = new Label();
        Button run = new Button("Прогнать судью (dry-run)");
        run.setDisable(judgeDryRunHandler == null);
        run.setOnAction(e -> {
            verdict.setText("Running…");
            verdict.setStyle("");
            judgeDryRunHandler.dryRun(judge.id(), outcome -> {
                verdict.setText((outcome.passed() ? "PASS" : "FAIL") + " — " + outcome.detail());
                verdict.setStyle(outcome.passed() ? "-fx-text-fill: #1a7f37;" : "-fx-text-fill: #c62828;");
            });
        });
        HBox bar = new HBox(8, run, verdict);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(6));
        return bar;
    }

    // ---- shared helpers --------------------------------------------------------------------

    private static TextArea diffArea() {
        TextArea diffArea = new TextArea();
        diffArea.setEditable(false);
        diffArea.setPrefRowCount(8);
        diffArea.setStyle("-fx-font-family: monospace;");
        return diffArea;
    }

    private static TitledPane collapsedDiffPane(TextArea diffArea) {
        TitledPane pane = new TitledPane("Diff with git HEAD", diffArea);
        pane.setExpanded(false);
        return pane;
    }

    private void loadDiffAsync(Path relativePath, TextArea target) {
        if (projectRoot == null) {
            return;
        }
        Thread.ofVirtual().start(() -> {
            String diff = GitDiffReader.read(projectRoot, relativePath, GIT_DIFF_TIMEOUT);
            Platform.runLater(() -> target.setText(diff));
        });
    }

    private boolean confirmContractRemoval() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "The result-contract JSON block (step_id/status/artifacts/pending_questions) "
                        + "looks removed or changed. The engine expects this shape at the end of every "
                        + "agent phase — saving without it may break the step's result parsing.\n\n"
                        + "Save anyway?");
        confirm.setHeaderText("Contract block missing");
        return confirm.showAndWait().filter(b -> b == ButtonType.OK).isPresent();
    }

    private static String readOrEmpty(Path absolute) {
        if (absolute == null || !Files.isRegularFile(absolute)) {
            return "";
        }
        try {
            return Files.readString(absolute, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read " + absolute, e);
        }
    }
}
