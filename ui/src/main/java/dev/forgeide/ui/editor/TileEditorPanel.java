package dev.forgeide.ui.editor;

import dev.forgeide.core.pipeline.AgentStep;
import dev.forgeide.core.pipeline.JudgeStep;
import dev.forgeide.core.pipeline.ScriptStep;
import dev.forgeide.core.pipeline.StepDefinition;
import dev.forgeide.core.pipeline.TileValidity;
import dev.forgeide.core.pipeline.validation.PipelineError;
import dev.forgeide.runtime.git.GitDiffReader;
import dev.forgeide.runtime.harness.JudgeScriptLocator;
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

    private static final Duration GIT_DIFF_TIMEOUT = Duration.ofSeconds(5);

    private final Path projectRoot;
    private final PromptSaveHandler promptSaveHandler;
    private final ScriptSaveHandler scriptSaveHandler;
    private final TileDetailPanel configPanel = new TileDetailPanel();

    public TileEditorPanel(Path projectRoot, PromptSaveHandler promptSaveHandler, ScriptSaveHandler scriptSaveHandler) {
        this.projectRoot = projectRoot;
        this.promptSaveHandler = promptSaveHandler;
        this.scriptSaveHandler = scriptSaveHandler;
        setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        showEmpty();
    }

    public void showEmpty() {
        configPanel.showEmpty();
        getTabs().setAll(new Tab("Config", configPanel));
    }

    public void show(StepDefinition step, List<PipelineError> errors, TileValidity validity) {
        configPanel.show(step, errors, validity);

        List<Tab> tabs = new ArrayList<>();
        promptTarget(step).ifPresent(target ->
                tabs.add(nonClosable("Prompt", promptEditor(target.stepId(), target.relativePath()))));
        scriptTarget(step).ifPresent(relativePath ->
                tabs.add(nonClosable("Script", scriptEditor(relativePath))));
        tabs.add(nonClosable("Config", configPanel));
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

        Button diffButton = new Button("Diff with git HEAD");
        diffButton.setDisable(projectRoot == null);
        diffButton.setOnAction(e -> {
            diffPane.setExpanded(true);
            loadDiffAsync(relativePath, diffArea);
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

        HBox toolbar = new HBox(8, save, diffButton, status);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(6));

        VirtualizedScrollPane<PromptCodeArea> scrollPane = new VirtualizedScrollPane<>(area);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        VBox box = new VBox(toolbar, scrollPane, diffPane);
        return box;
    }

    // ---- Script tab ------------------------------------------------------------------------

    private Node scriptEditor(Path relativePath) {
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

        return new VBox(toolbar, scrollPane, diffPane);
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
