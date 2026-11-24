package com.scientificlens.harness.ui;

import com.scientificlens.harness.core.AnalysisLibrary;
import com.scientificlens.harness.core.Orchestrator;
import com.scientificlens.harness.domain.Mode;
import com.scientificlens.harness.domain.UserProblem;
import com.scientificlens.harness.llm.CliEngine;
import com.scientificlens.harness.llm.CliProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.MenuButton;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Native submission checks use a separate JVM so its library, settings and CLI search path are isolated. */
@EnabledIfSystemProperty(named = "slh.submission.ui", matches = "true")
class AnalysisSubmissionUiTest {
    private MainApp app;
    private Stage stage;
    private Scene scene;

    @ParameterizedTest
    @ValueSource(strings = {"pdf", "missing-cli"})
    void nativeSubmission(String scenario, @TempDir Path sandbox) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
        Path output = sandbox.resolve("scenario.log");
        ProcessBuilder command = new ProcessBuilder(java.toString(), "--enable-native-access=ALL-UNNAMED",
                "-Duser.home=" + sandbox, "-Dslh.reducedMotion=true", "-Dstdout.encoding=UTF-8",
                "-Dstderr.encoding=UTF-8", "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                getClass().getName(), scenario);
        command.environment().put("PATH", sandbox.toString());
        Process process = command.directory(sandbox.toFile()).redirectErrorStream(true)
                .redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(90, TimeUnit.SECONDS), "Native submission check timed out");
            assertEquals(0, process.exitValue(), Files.readString(output));
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
        }
    }

    public static void main(String[] args) throws Exception {
        var test = new AnalysisSubmissionUiTest();
        Platform.startup(() -> Platform.setImplicitExit(false));
        try {
            fx(() -> {
                test.app = new MainApp();
                test.stage = new Stage();
                test.app.start(test.stage);
                test.scene = test.stage.getScene();
                Button enter = test.button("opening-enter");
                if (enter != null) enter.fire();
                return null;
            });
            if ("pdf".equals(args[0])) test.exportPreservesReport();
            else test.missingCliPreservesReport();
        } finally {
            fx(() -> {
                if (test.app != null) test.app.stop();
                Window.getWindows().stream().filter(Window::isShowing).toList().forEach(Window::hide);
                return null;
            });
            Platform.exit();
        }
    }

    private void exportPreservesReport() throws Exception {
        fx(() -> {
            button("settings-button").fire();
            DialogPane settings = dialog();
            ((ToggleButton) settings.lookup("#engine-mock")).fire();
            ((Button) settings.lookupButton(ButtonType.CLOSE)).fire();
            TextArea draft = input("welcome-question");
            draft.setText("   \n ");
            shortcut(draft);
            assertTrue(button("welcome-start").isDisabled());
            assertTrue(button("run-analysis").isDisabled());
            assertTrue(button("cancel-analysis").isDisabled());
            draft.setText("A mock report for the PDF");
            shortcut(draft);
            assertFalse(button("cancel-analysis").isDisabled());
            return null;
        });
        await(() -> !exportMenu().isDisabled());
        Path pdf = Path.of(System.getProperty("user.home"), "report.pdf");
        var duringExport = fx(() -> {
            ((ToggleButton) scene.lookup("#reading-focus")).setSelected(false);
            app.exportPdf(pdf);
            boolean runDisabled = button("run-analysis").isDisabled();
            boolean welcomeDisabled = button("welcome-start").isDisabled();
            shortcut(input("question-input"));
            return List.of(runDisabled, welcomeDisabled, button("cancel-analysis").isDisabled());
        });
        await(() -> dialog() != null);
        fx(() -> {
            assertEquals("pdf-export-success", dialog().getId());
            ((Button) dialog().lookupButton(ButtonType.OK)).fire();
            assertFalse(button("run-analysis").isDisabled());
            assertFalse(button("welcome-start").isDisabled());
            return null;
        });
        assertTrue(Files.size(pdf) > 0);
        assertEquals(List.of(true, true, true), duringExport,
                "Both buttons and Ctrl+Enter must stay inactive until the PDF finishes");
    }

    private void missingCliPreservesReport() throws Exception {
        assertFalse(CliEngine.isInstalled(CliProvider.KIRO), "This process must have no Kiro executable");
        var previous = Orchestrator.analyzeMock(new UserProblem("Previously completed report", Mode.FAST));
        var stored = new AnalysisLibrary(Path.of(System.getProperty("user.home"), "fixtures")).save(previous);
        fx(() -> {
            app.restoreReport(stored);
            button("home-button").fire();
            button("welcome-start").fire();
            String checking = button("welcome-start").getText();
            button("welcome-workspace").fire();
            button("cancel-analysis").fire();
            assertAll(() -> assertEquals("CLI 확인 중…", checking),
                    () -> assertFalse(button("run-analysis").isDisabled(), "Cancelling must unblock the draft immediately"),
                    () -> assertFalse(exportMenu().isDisabled()));
            return null;
        });
        for (String action : List.of("welcome-start", "run-analysis", "shortcut")) {
            boolean home = action.equals("welcome-start");
            boolean keptScreen = fx(() -> {
                app.restoreReport(stored);
                if (home) button("home-button").fire();
                else ((ToggleButton) scene.lookup("#reading-focus")).setSelected(false);
                input(home ? "welcome-question" : "question-input").setText("New draft");
                if (action.equals("shortcut")) shortcut(input("question-input"));
                else button(action).fire();
                boolean kept = scene.lookup("#welcome-view").isVisible() == home;
                if (!kept) button("cancel-analysis").fire();
                return kept;
            });
            assertTrue(keptScreen, "Checking a missing CLI must preserve the current screen for " + action);
            await(() -> dialog() != null);
            fx(() -> {
                DialogPane alert = dialog();
                String message = alert.getContentText();
                ((Button) alert.lookupButton(ButtonType.OK)).fire();
                assertTrue(message.contains("kiro-cli"), message);
                assertEquals(home, scene.lookup("#welcome-view").isVisible(), action);
                assertFalse(exportMenu().isDisabled(), "The previous report should still be available after " + action);
                assertEquals("New draft", input("question-input").getText());
                return null;
            });
        }
    }

    private Button button(String id) { return (Button) scene.lookup("#" + id); }
    private TextArea input(String id) { return (TextArea) scene.lookup("#" + id); }
    private MenuButton exportMenu() { return (MenuButton) scene.lookup("#export-menu"); }

    private DialogPane dialog() {
        return Window.getWindows().stream().filter(w -> w != stage && w.isShowing())
                .map(w -> w.getScene().getRoot()).filter(DialogPane.class::isInstance)
                .map(DialogPane.class::cast).findFirst().orElse(null);
    }

    private static void shortcut(TextArea input) {
        input.requestFocus();
        boolean mac = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("mac");
        Event.fireEvent(input, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER,
                false, !mac, false, mac));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(20, TimeUnit.SECONDS);
    }

    private static void await(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (!fx(condition) && System.nanoTime() < deadline) Thread.sleep(40);
        assertTrue(fx(condition), "Native UI timed out");
    }
}
