package com.scientificlens.harness.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.scientificlens.harness.core.AnalysisLibrary;
import com.scientificlens.harness.domain.Mode;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@EnabledIfSystemProperty(named = "slh.opening.ui", matches = "true")
final class CouncilOpeningUiTest {
    private static final String OPENING = "#council-opening";
    private static final Path CAPTURES = Path.of("target", "cp4-opening-qa");
    @TempDir Path libraryDirectory;
    private String originalLibrary;
    private MainApp app;
    private Stage stage;
    private Scene scene;

    @BeforeAll
    static void startToolkit() throws Exception {
        Files.createDirectories(CAPTURES);
        var ready = new CompletableFuture<Void>();
        Platform.startup(() -> { Platform.setImplicitExit(false); ready.complete(null); });
        ready.get(20, TimeUnit.SECONDS);
    }

    @BeforeEach
    void openWindow() throws Exception {
        originalLibrary = System.setProperty("slh.library.path", libraryDirectory.toString());
        onFx(() -> {
            app = new MainApp();
            stage = new Stage();
            app.start(stage);
            scene = stage.getScene();
        });
        size(1280, 860);
    }

    @AfterEach
    void closeWindow() throws Exception {
        try {
            onFx(() -> { if (stage != null) stage.hide(); if (app != null) app.stop(); });
        } finally {
            if (originalLibrary == null) System.clearProperty("slh.library.path");
            else System.setProperty("slh.library.path", originalLibrary);
        }
    }

    @AfterAll
    static void stopToolkit() { Platform.exit(); }

    /** The existing workbench scenarios use the same public entry controls on the FX thread. */
    static void enterForWorkspaceTests(Scene scene) {
        var motion = assertInstanceOf(ToggleButton.class, scene.lookup("#opening-motion"));
        boolean originallyReduced = motion.isSelected();
        if (!originallyReduced) motion.fire();
        assertInstanceOf(Button.class, scene.lookup("#opening-enter")).fire();
        assertNull(scene.lookup(OPENING), "Reduced motion must enter the studio immediately");
        if (!originallyReduced) assertInstanceOf(ToggleButton.class, scene.lookup("#reduce-motion")).fire();
    }

    @Test
    void openingFitsTheWindowAndLocalizesWithoutEntering() throws Exception {
        await("title arrival", () -> fx(() -> scene.lookup("#opening-title").getParent()
                .getChildrenUnmodifiable().stream().allMatch(line -> line.getOpacity() == 1)));
        for (int[] viewport : List.of(new int[]{1280, 860}, new int[]{1000, 740}, new int[]{1568, 1003})) {
            size(viewport[0], viewport[1]);
            onFx(() -> {
                var cover = sceneBounds(scene.lookup(OPENING));
                assertEquals(0, cover.getMinX(), 1);
                assertEquals(0, cover.getMinY(), 1);
                assertEquals(scene.getWidth(), cover.getWidth(), 1);
                assertEquals(scene.getHeight(), cover.getHeight(), 1);
                assertTrue(sceneBounds(scene.lookup("#opening-art")).getWidth() > 0);
                for (String id : List.of("title", "enter", "language-ko", "language-en", "language-ja", "language-zh", "motion")) {
                    Node node = scene.lookup("#opening-" + id);
                    assertTrue(node.isVisible() && cover.contains(sceneBounds(node)), id + " outside the opening");
                }
                assertEquals("노벨리스트", ((Label) scene.lookup("#opening-title")).getText());
            });
            capture("opening-ko");
        }
        size(1280, 860);
        record Translation(String code, String title) {}
        for (var language : List.of(new Translation("en", "Nobellist"), new Translation("ja", "ノーベリスト"),
                new Translation("zh", "诺贝利斯特"))) {
            onFx(() -> {
                var control = (ToggleButton) scene.lookup("#opening-language-" + language.code());
                if (language.code().equals("en")) {
                    control.requestFocus();
                    key(KeyCode.SPACE, false);
                } else {
                    click(control);
                }
                assertNotNull(scene.lookup(OPENING));
                assertTrue(control.isSelected());
                assertEquals(language.title(), ((Label) scene.lookup("#opening-title")).getText());
            });
            capture("opening-" + language.code());
        }
        onFx(() -> {
            var motion = (ToggleButton) scene.lookup("#opening-motion");
            if (!motion.isSelected()) motion.fire();
            Node first = scene.lookup("#opening-language-ko");
            var left = sceneBounds(first);
            var right = sceneBounds(scene.lookup("#opening-language-en"));
            clickAt(first.getParent(), (left.getMaxX() + right.getMinX()) / 2, left.getCenterY());
            assertNotNull(scene.lookup(OPENING), "Clicking inside the language tray must stay on the opening");
        });
    }

    @Test
    void constellationLabelsAreCenteredUnderTheirIconsAtEveryWindowSize() throws Exception {
        onFx(() -> enterForWorkspaceTests(scene));
        for (int[] window : List.of(new int[]{1000, 740}, new int[]{1280, 860}, new int[]{1568, 1003})) {
            size(window[0], window[1]);
            onFx(() -> {
                Node core = scene.lookup(".welcome-core");
                assertCenteredLines(core, (Label) core.lookup(".welcome-map-core"));
                var lenses = scene.getRoot().lookupAll(".welcome-lens");
                assertEquals(5, lenses.size());
                for (Node lens : lenses) {
                    assertCenteredLines(lens, (Label) lens.lookup(".welcome-map-name"));
                }
            });
            capture("workspace-composer");
        }
    }

    private static void assertCenteredLines(Node card, Label label) {
        Text glyphs = (Text) label.lookup(".text");
        int start = 0;
        for (String line : label.getText().split("\\n")) {
            var selection = new javafx.scene.shape.Path(glyphs.rangeShape(start, start + line.length()));
            selection.setFill(Color.BLACK);
            selection.setStroke(null);
            Bounds lineBounds = glyphs.localToScene(selection.getLayoutBounds());
            assertEquals(sceneBounds(card).getCenterX(), lineBounds.getCenterX(), 2, line);
            start += line.length() + 1;
        }
    }

    @Test
    void clickTransitionsToTheComposerAndItsShortcutRunsOneMockReport() throws Exception {
        onFx(() -> {
            ToggleButton motion = (ToggleButton) scene.lookup("#opening-motion");
            if (motion.isSelected()) motion.fire();
            scene.lookup(OPENING).requestFocus();
            key(KeyCode.L, true);
            assertNull(dialogContaining("#library-list"), "The library shortcut leaked through the opening");
        });
        var frames = fx(() -> {
            Node cover = scene.lookup(OPENING);
            click(cover);
            click(cover);
            return List.of(transitionFrame(350), transitionFrame(700));
        });
        BufferedImage zoom = frames.getFirst().get(10, TimeUnit.SECONDS);
        BufferedImage iris = frames.getLast().get(10, TimeUnit.SECONDS);
        int changed = 0;
        for (int y = 40; y < iris.getHeight(); y += 80) {
            for (int x = 40; x < iris.getWidth(); x += 80) {
                if (zoom.getRGB(x, y) != iris.getRGB(x, y)) changed++;
            }
        }
        assertTrue(changed > 20, "Zoom and iris must render as distinct visible frames");
        write("entry-transition-mid", zoom);
        write("entry-transition-iris", iris);
        await("composer focus after animated entry", () -> fx(this::composerHasFocus));
        capture("workspace-composer");
        var library = new AnalysisLibrary(libraryDirectory);
        String question = fx(() -> {
            key(KeyCode.L, true);
            close(assertNotNullDialog("#library-list"));
            ((Button) scene.lookup("#settings-button")).fire();
            var settings = assertNotNullDialog("#engine-mock");
            ((ToggleButton) settings.lookup("#engine-mock")).fire();
            close(settings);
            ((Button) scene.lookup("#welcome-example-2")).fire();
            ((ToggleButton) scene.lookup("#welcome-mode-fast")).fire();
            var composer = (TextArea) scene.lookup("#welcome-question");
            composer.requestFocus();
            assertFalse(composer.getText().isBlank());
            key(KeyCode.ENTER, true);
            return composer.getText();
        });
        await("mock report saved", () -> !library.list(false).entries().isEmpty());
        var records = library.list(false).entries();
        assertEquals(1, records.size());
        var report = records.getFirst().result();
        assertEquals("mock", report.runtime());
        assertEquals(question, report.problem().question());
        assertEquals(Mode.FAST, report.problem().mode());
        assertFalse(report.lensOutputs().isEmpty());
        onFx(() -> {
            assertFalse(scene.lookup("#welcome-view").isVisible());
            assertEquals(0, ((TabPane) scene.lookup("#result-tabs")).getSelectionModel().getSelectedIndex());
            assertFalse(((MenuButton) scene.lookup("#report-export-menu")).isDisabled());
        });
        capture("workspace-mock-result");
        onFx(() -> {
            ((Button) scene.lookup("#home-button")).fire();
            assertNull(scene.lookup(OPENING));
            assertEquals(question, ((TextArea) scene.lookup("#welcome-question")).getText());
        });
    }

    @ParameterizedTest
    @EnumSource(value = KeyCode.class, names = {"ENTER", "SPACE"})
    void reducedMotionEntersImmediatelyFromTheKeyboard(KeyCode entryKey) throws Exception {
        onFx(() -> {
            var motion = (ToggleButton) scene.lookup("#opening-motion");
            if (!motion.isSelected()) click(motion);
            assertTrue(motion.isSelected());
            scene.lookup(OPENING).requestFocus();
        });
        if (entryKey == KeyCode.ENTER) capture("opening-reduced-motion");
        onFx(() -> {
            key(entryKey, false);
            assertNull(scene.lookup(OPENING), "Reduced motion must finish on the activation event");
            assertTrue(((ToggleButton) scene.lookup("#reduce-motion")).isSelected());
        });
        await("composer focus after " + entryKey, () -> fx(this::composerHasFocus));
        assertTrue(fx(() -> ((TextArea) scene.lookup("#welcome-question")).getText().isEmpty()));
    }

    private boolean composerHasFocus() {
        return scene.lookup(OPENING) == null && scene.getFocusOwner() == scene.lookup("#welcome-question");
    }

    private void size(int width, int height) throws Exception {
        onFx(() -> { stage.setWidth(width); stage.setHeight(height); });
        await("scene resize to " + width + "x" + height, () -> fx(() ->
                Math.round(scene.getWidth()) == width && Math.round(scene.getHeight()) == height));
        onFx(() -> { scene.getRoot().applyCss(); scene.getRoot().layout(); });
    }

    private static Bounds sceneBounds(Node node) { return node.localToScene(node.getLayoutBounds()); }

    private void key(KeyCode code, boolean shortcut) {
        boolean mac = System.getProperty("os.name").startsWith("Mac");
        for (var type : List.of(KeyEvent.KEY_PRESSED, KeyEvent.KEY_TYPED, KeyEvent.KEY_RELEASED)) {
            Node focus = scene.getFocusOwner();
            if (type == KeyEvent.KEY_TYPED) {
                if (!shortcut && (code == KeyCode.ENTER || code == KeyCode.SPACE)) {
                    String typed = code == KeyCode.SPACE ? " " : "\r";
                    Event.fireEvent(focus == null ? scene : focus, new KeyEvent(type, typed,
                            "", KeyCode.UNDEFINED, false, false, false, false));
                }
            } else {
                Event.fireEvent(focus == null ? scene : focus, new KeyEvent(type, KeyEvent.CHAR_UNDEFINED,
                        code.getName(), code, false, shortcut && !mac, false, shortcut && mac));
            }
        }
    }

    private void click(Node node) {
        var bounds = sceneBounds(node);
        double x = node == scene.lookup(OPENING) ? 20 : bounds.getCenterX();
        clickAt(node, x, bounds.getCenterY());
    }

    private void clickAt(Node node, double x, double y) {
        for (var type : List.of(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED)) {
            node.fireEvent(new MouseEvent(type, x, y, stage.getX() + x, stage.getY() + y, MouseButton.PRIMARY, 1,
                    false, false, false, false, type == MouseEvent.MOUSE_PRESSED, false, false,
                    false, false, true, new PickResult(node, x, y)));
        }
    }

    private static DialogPane dialogContaining(String selector) {
        return Window.getWindows().stream().filter(Window::isShowing).map(Window::getScene)
                .map(Scene::getRoot).filter(DialogPane.class::isInstance).map(DialogPane.class::cast)
                .filter(pane -> pane.lookup(selector) != null).findFirst().orElse(null);
    }

    private static DialogPane assertNotNullDialog(String selector) {
        return assertInstanceOf(DialogPane.class, dialogContaining(selector), "Expected dialog containing " + selector);
    }

    private static void close(DialogPane pane) { ((Button) pane.lookupButton(ButtonType.CLOSE)).fire(); }

    private CompletableFuture<BufferedImage> transitionFrame(int milliseconds) {
        var result = new CompletableFuture<BufferedImage>();
        var sample = new PauseTransition(Duration.millis(milliseconds));
        sample.setOnFinished(event -> {
            try {
                assertNotNull(scene.lookup(OPENING), "Transition ended before " + milliseconds + " ms");
                result.complete(frame());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        sample.play();
        return result;
    }

    private BufferedImage frame() {
        int width = (int) Math.round(scene.getWidth()), height = (int) Math.round(scene.getHeight());
        var image = scene.snapshot(new WritableImage(width, height));
        var result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = new int[width * height];
        image.getPixelReader().getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width);
        result.setRGB(0, 0, width, height, pixels, 0, width);
        return result;
    }

    private void capture(String name) throws Exception { write(name, fx(this::frame)); }

    private static void write(String name, BufferedImage image) throws Exception {
        String file = name + "-" + image.getWidth() + "x" + image.getHeight() + ".png";
        assertTrue(ImageIO.write(image, "png", CAPTURES.resolve(file).toFile()));
    }

    private static void await(String description, Callable<Boolean> condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (!condition.call()) {
            assertTrue(System.nanoTime() < end, "Timed out waiting for " + description);
            Thread.sleep(30);
        }
    }

    private static void onFx(Runnable action) throws Exception {
        fx(() -> { action.run(); return null; });
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(20, TimeUnit.SECONDS);
    }
}
