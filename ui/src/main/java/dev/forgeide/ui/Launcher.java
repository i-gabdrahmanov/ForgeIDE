package dev.forgeide.ui;

import javafx.application.Application;

// Separate from App to avoid "JavaFX runtime components are missing" when launched as the main class.
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        Application.launch(App.class, args);
    }
}
