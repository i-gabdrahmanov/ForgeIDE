package dev.forgeide.ui;

import dev.forgeide.core.project.json.ProjectRegistry;
import dev.forgeide.runtime.project.ProcessRuntimeAvailabilityChecker;
import dev.forgeide.ui.project.ProjectsController;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class App extends Application {

    @Override
    public void start(Stage primaryStage) {
        ProjectsController controller = new ProjectsController(new ProjectRegistry(),
                new ProcessRuntimeAvailabilityChecker());
        var scene = new Scene(controller.root(), 900, 640);

        primaryStage.setTitle("ForgeIDE");
        primaryStage.setScene(scene);
        primaryStage.show();
    }
}
