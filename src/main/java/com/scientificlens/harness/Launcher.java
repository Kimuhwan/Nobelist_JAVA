package com.scientificlens.harness;

import com.scientificlens.harness.ui.MainApp;
import javafx.application.Application;

/** Plain main class so the shaded jar can start JavaFX from the classpath. */
public final class Launcher {
    private Launcher() {}

    public static void main(String[] args) {
        Application.launch(MainApp.class, args);
    }
}
