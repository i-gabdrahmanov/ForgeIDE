package dev.forgeide.ui;

import dev.forgeide.core.project.json.ProjectRegistry;
import dev.forgeide.runtime.project.ProcessRuntimeAvailabilityChecker;
import dev.forgeide.ui.project.ProjectsController;
import dev.forgeide.ui.run.RunEngineRegistry;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class App extends Application {

    private final RunEngineRegistry engineRegistry = new RunEngineRegistry();

    @Override
    public void start(Stage primaryStage) {
        ProjectsController controller = new ProjectsController(new ProjectRegistry(),
                new ProcessRuntimeAvailabilityChecker(), engineRegistry);
        var scene = new Scene(controller.root(), 900, 640);

        primaryStage.setTitle("ForgeIDE");
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    @Override
    public void stop() {
        // Any run still in flight when the IDE quits must not leak its engine's actor thread
        // and virtual-thread workers (RunEngineRegistry only closes an engine early once its
        // run reaches a terminal status).
        engineRegistry.closeAll();
    }
}
