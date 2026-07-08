package dev.forgeide.ui.run;

import dev.forgeide.core.pipeline.StepDefinition;
import dev.forgeide.core.port.AgentEvent;
import dev.forgeide.runtime.agent.StreamJsonEvents;
import dev.forgeide.runtime.logtail.FileTailer;
import dev.forgeide.runtime.logtail.LineRingBuffer;
import dev.forgeide.runtime.process.LineClassifier;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

/**
 * Step-log tabs (SDD FR-7.7): parsed events / raw stdout / raw stderr / meta, full-text search,
 * follow-mode. Plain {@link ListView} rather than RichTextFX (SD §7 reserves RichTextFX for the
 * prompt/judge editor, not high-volume logs) — {@link ListView} is virtualized by JavaFX so a
 * 10k-line {@link LineRingBuffer} renders cheaply. Re-targeted whenever the canvas selection or
 * the selected step's current iteration changes; {@link #dispose()} must be called then too, so
 * a stale iteration's tailer doesn't keep polling forever.
 */
public final class StepLogView extends BorderPane {

    private final Path projectRoot;
    private final String featureSlug;

    private LogTab parsedTab;
    private LogTab rawStdoutTab;
    private LogTab rawStderrTab;
    private Label metaLabel;
    private Path currentMetaFile;

    public StepLogView(Path projectRoot, String featureSlug) {
        this.projectRoot = projectRoot;
        this.featureSlug = featureSlug;

        parsedTab = new LogTab();
        rawStdoutTab = new LogTab();
        rawStderrTab = new LogTab();
        metaLabel = new Label("Select a step to see its logs.");
        metaLabel.setWrapText(true);
        VBox metaBox = new VBox(8, metaLabel);
        metaBox.setPadding(new Insets(12));

        TabPane tabs = new TabPane(
                new Tab("Parsed", parsedTab.root()),
                new Tab("Raw stdout", rawStdoutTab.root()),
                new Tab("Raw stderr", rawStderrTab.root()),
                new Tab("Meta", metaBox));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        setCenter(tabs);
    }

    /** Points every tab at {@code def}'s current iteration, or shows "no output" if it has none. */
    public void setTarget(StepDefinition def, int iteration) {
        dispose();
        StepLogLocator.StepLogLocation location = StepLogLocator.locate(projectRoot, featureSlug, def, iteration);
        if (location instanceof StepLogLocator.StepLogLocation.NoOutput noOutput) {
            parsedTab.showUnavailable(noOutput.reason());
            rawStdoutTab.showUnavailable(noOutput.reason());
            rawStderrTab.showUnavailable(noOutput.reason());
            metaLabel.setText(noOutput.reason());
            currentMetaFile = null;
            return;
        }
        Path dir = ((StepLogLocator.StepLogLocation.Directory) location).dir();
        Path stdout = dir.resolve("stdout.jsonl");
        Path stderr = dir.resolve("stderr.log");
        currentMetaFile = dir.resolve("meta.json");
        refreshMeta();

        rawStdoutTab.tailFile(stdout, line -> List.of(line));
        parsedTab.tailFile(stdout, StepLogView::formatParsedLine);
        rawStderrTab.tailFile(stderr, line -> List.of(line));
    }

    /** Re-reads {@code meta.json} — it's written once, at process exit, so this is a one-shot read, never tailed. */
    public void refreshMeta() {
        if (currentMetaFile == null) {
            return;
        }
        Path file = currentMetaFile;
        Thread.ofVirtual().start(() -> {
            String text;
            try {
                text = Files.readString(file);
            } catch (IOException notYet) {
                text = "meta.json not available yet";
            }
            String finalText = text;
            Platform.runLater(() -> metaLabel.setText(finalText));
        });
    }

    /** Stops all tailers for the previous target; safe to call even if nothing was tailing. */
    public void dispose() {
        parsedTab.close();
        rawStdoutTab.close();
        rawStderrTab.close();
    }

    private static List<String> formatParsedLine(String rawLine) {
        List<AgentEvent> events = StreamJsonEvents.parse(LineClassifier.classify(rawLine));
        return events.stream().map(StepLogView::formatEvent).toList();
    }

    private static String formatEvent(AgentEvent event) {
        return switch (event) {
            case AgentEvent.ToolUse t -> "tool_use  " + t.name() + " " + t.input();
            case AgentEvent.Usage u -> "usage     in=" + u.usage().inputTokens() + " out=" + u.usage().outputTokens();
            case AgentEvent.Result r -> "result    " + r.finalJson();
            case AgentEvent.RawLine r -> r.line();
        };
    }

    /** One tab's worth of UI: search box, follow toggle, dropped-lines label, virtualized list. */
    private static final class LogTab {

        private LineRingBuffer buffer = new LineRingBuffer();
        private final ObservableList<String> lines = FXCollections.observableArrayList();
        private final FilteredList<String> filtered = new FilteredList<>(lines, s -> true);
        private final ListView<String> listView = new ListView<>(filtered);
        private final TextField search = new TextField();
        private final ToggleButton follow = new ToggleButton("Follow");
        private final Label dropped = new Label();
        private final VBox root;
        private FileTailer tailer;

        LogTab() {
            follow.setSelected(true);
            search.setPromptText("Search…");
            search.textProperty().addListener((obs, old, query) ->
                    filtered.setPredicate(line -> LogSearch.matches(line, query)));

            HBox toolbar = new HBox(8, search, follow, dropped);
            toolbar.setAlignment(Pos.CENTER_LEFT);
            toolbar.setPadding(new Insets(6));
            HBox.setHgrow(search, Priority.ALWAYS);

            root = new VBox(4, toolbar, listView);
            VBox.setVgrow(listView, Priority.ALWAYS);
        }

        VBox root() {
            return root;
        }

        void showUnavailable(String reason) {
            close();
            lines.setAll(reason);
        }

        /** {@code toDisplayLines} maps one raw file line to zero or more display lines (e.g. parsed events). */
        void tailFile(Path file, Function<String, List<String>> toDisplayLines) {
            lines.clear();
            dropped.setText("");
            buffer = new LineRingBuffer();
            LineRingBuffer targetBuffer = buffer;
            tailer = new FileTailer(file, targetBuffer, batch -> Platform.runLater(() -> {
                batch.newLines().forEach(raw -> lines.addAll(toDisplayLines.apply(raw)));
                long droppedCount = targetBuffer.droppedCount();
                dropped.setText(droppedCount > 0 ? droppedCount + " lines dropped" : "");
                if (follow.isSelected() && !filtered.isEmpty()) {
                    listView.scrollTo(filtered.size() - 1);
                }
            }));
        }

        void close() {
            if (tailer != null) {
                tailer.close();
                tailer = null;
            }
        }
    }
}
