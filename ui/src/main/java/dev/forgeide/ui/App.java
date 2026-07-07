package dev.forgeide.ui;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

public class App extends Application {

    @Override
    public void start(Stage primaryStage) {
        var root = new StackPane(new Label("ForgeIDE"));
        var scene = new Scene(root, 800, 600);

        primaryStage.setTitle("ForgeIDE");
        primaryStage.setScene(scene);
        primaryStage.show();
    }
}
