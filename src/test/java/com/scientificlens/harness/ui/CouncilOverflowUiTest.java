package com.scientificlens.harness.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.PixelFormat;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "slh.overflow.ui", matches = "true")
final class CouncilOverflowUiTest {
    private static final Path OUT = Path.of("target/council-overflow-qa");
    private MainApp app;
    private Stage stage;
    private Scene scene;

    @Test
    void cancelledCouncilRemainsReachableWhenEditingReturnsAtMinimumWindow() throws Exception {
        Files.createDirectories(OUT);
        System.setProperty("slh.library.path", Files.createTempDirectory(OUT, "library-").toString());
        CompletableFuture<Void> ready = new CompletableFuture<>();
        Platform.startup(() -> { Platform.setImplicitExit(false); ready.complete(null); });
        ready.get(20, TimeUnit.SECONDS);
        try {
            fx(() -> { app = new MainApp(); stage = new Stage(); app.start(stage); scene = stage.getScene();
                CouncilOpeningUiTest.enterForWorkspaceTests(scene);
                ((Button) scene.lookup("#settings-button")).fire();
                var dialog = javafx.stage.Window.getWindows().stream().filter(window -> window.isShowing()
                        && window.getScene().lookup("#engine-mock") != null).findFirst().orElseThrow();
                ((ToggleButton) dialog.getScene().lookup("#engine-mock")).fire();
                ((Button) ((DialogPane) dialog.getScene().getRoot()).lookupButton(ButtonType.CLOSE)).fire();
                ((TextArea) scene.lookup("#welcome-question")).setText("취소 후에도 토론 기록에 접근할 수 있어야 한다");
                ((Button) scene.lookup("#welcome-start")).fire();
                ((Button) scene.lookup("#cancel-analysis")).fire(); return null; });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!fx(() -> ((Label) scene.lookup("#analysis-status")).getText().equals("취소됨"))) {
                assertTrue(System.nanoTime() < deadline); Thread.sleep(80);
            }
            StringBuilder receipt = new StringBuilder();
            for (int width : new int[]{1000,1280,1568}) {
                fx(() -> { stage.setWidth(width); stage.setHeight(width == 1000 ? 740 : width == 1280 ? 860 : 1003);
                    scene.getRoot().applyCss(); scene.getRoot().layout(); return null; });
                Thread.sleep(120);
                fx(() -> {
                    assertTrue(scene.lookup("#analysis-settings-region").isVisible());
                    Node node = scene.lookup("#council-node-coordinator");
                    ScrollPane scroll = null;
                    for (var parent = node.getParent(); parent != null; parent = parent.getParent()) {
                        if (parent instanceof ScrollPane found) { scroll = found; break; }
                    }
                    assertNotNull(scroll, "Cancelled council has no scroll owner; its bottom is inaccessible beneath the footer");
                    scroll.setVvalue(0); return null;
                });
                Thread.sleep(120);
                fx(() -> { capture("cancel-top-" + width);
                    ((Button) scene.lookup("#council-node-coordinator")).fire();
                    ((ScrollPane) scene.lookup("#council-scroll")).setVvalue(1); return null;
                });
                Thread.sleep(120);
                fx(() -> {
                    ScrollPane scroll = (ScrollPane) scene.lookup("#council-scroll");
                    scene.getRoot().applyCss(); scene.getRoot().layout();
                    Node viewport = scroll.lookup(".viewport");
                    var visible = viewport.localToScene(viewport.getLayoutBounds());
                    Node footer = ((BorderPane) scene.lookup("#council-stage")).getBottom();
                    var bottom = footer.localToScene(footer.getLayoutBounds());
                    assertTrue(bottom.getMinY() >= visible.getMinY() - 1 && bottom.getMaxY() <= visible.getMaxY() + 1,
                            "Council bottom cannot be brought fully into its scroll viewport");
                    capture("cancel-bottom-" + width);
                    receipt.append("PASS ").append(width).append(" editing visible; council footer reachable; node action works\n");
                    return null;
                });
            }
            Files.writeString(OUT.resolve("receipt.txt"), receipt);
        } finally {
            fx(() -> { if (app != null) app.stop(); if (stage != null) stage.hide(); return null; });
            Platform.exit();
        }
    }

    private void capture(String name) throws Exception {
        scene.getRoot().applyCss(); scene.getRoot().layout();
        var image = scene.snapshot(null);
        int w=(int)image.getWidth(), h=(int)image.getHeight(); int[] pixels=new int[w*h];
        image.getPixelReader().getPixels(0,0,w,h,PixelFormat.getIntArgbInstance(),pixels,0,w);
        BufferedImage output=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);
        output.setRGB(0,0,w,h,pixels,0,w); ImageIO.write(output,"png",OUT.resolve(name+".png").toFile());
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> { try { result.complete(action.call()); } catch(Throwable error) { result.completeExceptionally(error); } });
        return result.get(20,TimeUnit.SECONDS);
    }
}
