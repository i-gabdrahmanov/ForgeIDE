package dev.forgeide.ui;

import dev.forgeide.core.pipeline.PipelineDefinition;
import dev.forgeide.core.pipeline.yaml.PipelineYaml;
import dev.forgeide.core.project.ProjectDefinition;
import dev.forgeide.core.project.json.ProjectRegistry;
import dev.forgeide.runtime.project.ProcessRuntimeAvailabilityChecker;
import dev.forgeide.runtime.state.FileStateStore;
import dev.forgeide.runtime.state.ProjectHash;
import dev.forgeide.runtime.state.StartupRecovery;
import dev.forgeide.ui.project.ProjectsController;
import dev.forgeide.ui.run.RunEngineRegistry;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

public class App extends Application {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    private final RunEngineRegistry engineRegistry = new RunEngineRegistry();

    @Override
    public void start(Stage primaryStage) {
        ProjectRegistry registry = new ProjectRegistry();
        recoverInterruptedRuns(registry);

        ProjectsController controller = new ProjectsController(registry,
                new ProcessRuntimeAvailabilityChecker(), engineRegistry);
        var scene = new Scene(controller.root(), 900, 640);

        primaryStage.setTitle("ForgeIDE");
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    /**
     * SDD FR-3.4: before any {@link dev.forgeide.core.engine.PipelineEngine} exists, a persisted
     * {@code RUNNING} step can only mean the previous process was killed mid-phase — this is the
     * one point in the IDE's lifecycle that is true for every registered project at once. Runs
     * best-effort per project so one unparsable {@code pipeline.yaml} can't block recovery for
     * the rest (NFR-3: this whole sweep must stay well under the 5s "ready to retry" budget).
     */
    private static void recoverInterruptedRuns(ProjectRegistry registry) {
        for (ProjectDefinition project : registry.list()) {
            try {
                Path pipelinePath = project.repositoryPath().resolve(".forgeide").resolve("pipeline.yaml");
                if (!Files.isRegularFile(pipelinePath)) {
                    continue;
                }
                PipelineYaml.ParseResult parsed = new PipelineYaml().parseLenient(pipelinePath);
                for (PipelineDefinition pipeline : parsed.definition().stream().toList()) {
                    String projectHash = ProjectHash.of(project.repositoryPath());
                    FileStateStore store = new FileStateStore(FileStateStore.defaultRoot(projectHash, pipeline.id()));
                    StartupRecovery.recover(store);
                }
            } catch (RuntimeException ex) {
                log.warn("startup recovery failed for project {}", project.name(), ex);
            }
        }
    }

    @Override
    public void stop() {
        // Any run still in flight when the IDE quits must not leak its engine's actor thread
        // and virtual-thread workers (RunEngineRegistry only closes an engine early once its
        // run reaches a terminal status).
        engineRegistry.closeAll();
    }
}
