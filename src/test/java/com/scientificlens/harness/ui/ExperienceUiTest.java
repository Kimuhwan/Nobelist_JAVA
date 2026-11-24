package com.scientificlens.harness.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.scientificlens.harness.core.Json;
import com.scientificlens.harness.domain.AgentEvent;
import com.scientificlens.harness.domain.HarnessResult;
import com.scientificlens.harness.domain.StoredAnalysis;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.PixelFormat;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "slh.experience.ui", matches = "true")
final class ExperienceUiTest {
    private static final Path QA = Path.of("target/experience-ui-qa");
    private final List<String> captures = new ArrayList<>();
    private MainApp app;
    private Stage stage;
    private Scene scene;

    @Test
    void welcomeDraftRealMockTaskAndPublicEventVisualization() throws Exception {
        Files.createDirectories(QA);
        System.setProperty("slh.library.path", Files.createTempDirectory(QA, "library-").toString());
        CompletableFuture<Void> ready = new CompletableFuture<>();
        Platform.startup(() -> { Platform.setImplicitExit(false); ready.complete(null); });
        ready.get(20, TimeUnit.SECONDS);
        try {
            fx(() -> { app = new MainApp(); stage = new Stage(); app.start(stage); scene = stage.getScene();
                CouncilOpeningUiTest.enterForWorkspaceTests(scene); return null; });
            for (int width : List.of(1568, 1280, 1000)) {
                size(width);
                assertTrue(fx(() -> scene.lookup("#welcome-view").isVisible()));
                assertTrue(fx(() -> button("#welcome-start").isDisabled()));
                capture("welcome-empty-" + width);
                fx(() -> { ((ScrollPane) scene.lookup("#welcome-view")).setVvalue(1); return null; });
                capture("welcome-bottom-" + width);
                fx(() -> { ((ScrollPane) scene.lookup("#welcome-view")).setVvalue(0); return null; });
            }
            fx(() -> {
                button("#welcome-example-0").fire();
                ((ToggleButton) scene.lookup("#welcome-mode-fast")).fire();
                button("#welcome-lens-curie").fire();
                assertTrue(((ToggleButton) scene.lookup("#mode-fast")).isSelected());
                ((ToggleButton) scene.lookup("#welcome-mode-fast")).fire();
                assertTrue(((ToggleButton) scene.lookup("#welcome-mode-fast")).isSelected());
                assertEquals(text("#question-input").getText(), text("#welcome-question").getText());
                assertFalse(button("#welcome-start").isDisabled());
                return null;
            });
            capture("welcome-example-selected-1000");
            fx(() -> { button("#welcome-workspace").fire(); return null; });
            assertFalse(fx(() -> scene.lookup("#welcome-view").isVisible()));
            fx(() -> { button("#home-button").fire(); return null; });
            assertFalse(fx(() -> text("#welcome-question").getText().isBlank()));
            fx(() -> {
                button("#settings-button").fire();
                Dialog<?> dialog = field(app, "settingsDialog", Dialog.class);
                ((ToggleButton) dialog.getDialogPane().lookup("#engine-mock")).fire(); dialog.close();
                button("#welcome-start").fire();
                assertFalse(scene.lookup("#welcome-view").isVisible());
                assertTrue(field(app, "busy", BooleanProperty.class).get());
                assertFalse(scene.lookup("#analysis-settings-region").isVisible());
                assertTrue(button("#home-button").isDisabled());
                return null;
            });
            await(() -> fx(() -> !field(app, "busy", BooleanProperty.class).get()));
            HarnessResult mock = fx(() -> field(app, "lastResult", HarnessResult.class));
            assertNotNull(mock);
            assertTrue(mock.runtime().startsWith("mock"));
            assertFalse(fx(() -> ((MenuButton) scene.lookup("#report-export-menu")).isDisabled()));
            fx(() -> { button("#home-button").fire(); return null; });
            capture("welcome-return-to-result");
            fx(() -> { button("#welcome-workspace").fire(); return null; });
            assertSame(mock, fx(() -> field(app, "lastResult", HarnessResult.class)));

            Path actualPath = Path.of(System.getProperty("slh.experience.result"));
            HarnessResult actual = Json.MAPPER.readValue(Files.readString(actualPath), HarnessResult.class);
            assertFalse(actual.agentEvents().isEmpty());
            fx(() -> { app.restoreReport(new StoredAnalysis(1, java.util.UUID.randomUUID().toString(),
                    "2026-10-15T00:00:00Z", "2026-10-15T00:00:00Z", "UI QA saved public record", "", "imported", actual)); return null; });
            ActivityView activity = fx(() -> field(app, "activity", ActivityView.class));
            for (int width : List.of(1568, 1280, 1000)) {
                size(width);
                for (int index = 0; index < 7; index++) {
                    int tab = index;
                    fx(() -> { tabs().getSelectionModel().select(tab); return null; });
                    capture("result-tab-" + index + "-" + width);
                }
                fx(() -> { tabs().getSelectionModel().select(4); activity.begin(false);
                    activity.stage("UI 검증 · 저장된 공개 이벤트로 상태 전환 재생"); return null; });
                capture("council-planning-" + width);
                assertEquals("0", fx(() -> ((Label) scene.lookup("#council-count")).getText()).replaceAll("[^0-9]", ""));
                fx(() -> { activity.selectLenses(actual.selectedLenses()); return null; });
                capture("council-selected-" + width);
                int challenge = -1;
                for (int i = 0; i < actual.agentEvents().size(); i++) {
                    AgentEvent event = actual.agentEvents().get(i);
                    fx(() -> { activity.add(event); return null; });
                    if (event.action().equals("analyze") && i < 4) {
                        capture("council-analysis-" + width);
                    }
                    if (event.action().equals("challenge")) {
                        challenge = i;
                        capture("council-challenge-" + width);
                        fx(() -> { button("#council-node-" + event.recipient()).fire(); return null; });
                        capture("council-inspect-" + width);
                        assertTrue(fx(() -> scene.lookup("#council-detail").lookupAll(".label").stream()
                                .filter(Label.class::isInstance).map(Label.class::cast)
                                .anyMatch(label -> label.getText().equals(event.content()))));
                        fx(() -> { ((ScrollPane) scene.lookup("#council-detail-scroll")).setVvalue(1); return null; });
                        capture("council-inspect-bottom-" + width);
                        fx(() -> { ((ScrollPane) scene.lookup("#council-detail-scroll")).setVvalue(0); return null; });
                    }
                    if (event.action().equals("reply")) capture("council-reply-" + width);
                }
                assertTrue(challenge >= 0);
                fx(() -> { activity.finish("UI 검증 재생 완료 · 실제 신규 모델 호출 없음"); return null; });
                capture("council-finished-" + width);
                fx(() -> { ((ToggleButton) scene.lookup("#activity-transcript")).fire(); return null; });
                capture("council-transcript-" + width);
                fx(() -> { ((CheckBox) scene.lookup("#activity-raw")).fire(); return null; });
                capture("council-raw-" + width);
                fx(() -> { ((CheckBox) scene.lookup("#activity-raw")).fire();
                    ((ToggleButton) scene.lookup("#activity-transcript")).fire();
                    ((ToggleButton) scene.lookup("#reduce-motion")).setSelected(true);
                    activity.begin(false); activity.selectLenses(actual.selectedLenses());
                    activity.add(actual.agentEvents().stream().filter(e -> e.action().equals("analyze")).findFirst().orElseThrow()); return null; });
                capture("council-reduced-motion-" + width);
                fx(() -> { activity.finish("취소됨"); return null; });
                assertNoMotion();
                capture("council-cancelled-" + width);
                fx(() -> { activity.finish("실패 · 기록에서 오류를 확인하세요"); return null; });
                capture("council-error-" + width);
                fx(() -> { activity.replay(actual, "2026-10-15T00:00:00Z"); return null; });
                capture("council-saved-" + width);
                fx(() -> { activity.begin(false); activity.selectLenses(com.scientificlens.harness.core.LensRegistry.load()
                        .stream().map(profile -> profile.lensId()).toList()); return null; });
                capture("council-all15-" + width);
                fx(() -> {
                    var controls = com.scientificlens.harness.core.LensRegistry.load().stream()
                            .map(profile -> button("#council-node-" + profile.lensId())).toList();
                    for (int first = 0; first < controls.size(); first++) {
                        var a = controls.get(first).localToScene(controls.get(first).getLayoutBounds());
                        assertTrue(a.getMinX() >= 0 && a.getMaxX() <= scene.getWidth());
                        for (int second = first + 1; second < controls.size(); second++) {
                            var b = controls.get(second).localToScene(controls.get(second).getLayoutBounds());
                            assertFalse(a.intersects(b), "Lens controls overlap: " + controls.get(first).getId() + " / " + controls.get(second).getId());
                        }
                    }
                    return null;
                });
                fx(() -> { button("#council-node-oppenheimer").fire(); return null; });
                capture("council-all15-inspect-" + width);
                fx(() -> { activity.finish("UI 검증 · 전체 렌즈 배치 확인 완료"); return null; });
            }
            fx(() -> { ((ToggleButton) scene.lookup("#reduce-motion")).setSelected(false); activity.begin(false);
                activity.selectLenses(actual.selectedLenses()); return null; });
            AgentEvent analyze = actual.agentEvents().stream().filter(e -> e.action().equals("analyze")).findFirst().orElseThrow();
            fx(() -> { activity.add(analyze); captureNow("motion-start"); return null; });
            Thread.sleep(100); fx(() -> { captureNow("motion-mid"); return null; });
            Thread.sleep(650); fx(() -> { captureNow("motion-settled"); activity.finish("UI 검증 완료"); return null; });
            assertNoMotion();
            Files.writeString(QA.resolve("captures.txt"), String.join("\n", captures));
            Files.writeString(QA.resolve("receipt.txt"), "PASS native welcome empty/example/mode/draft/return controls\n"
                    + "PASS actual Mock task starts from welcome, hides settings, finishes with export enabled\n"
                    + "PASS all seven report tabs and graphical council states at 1568/1280/1000\n"
                    + "PASS genuine saved public events replayed for graphical state QA; no new LLM inference\n"
                    + "PASS full transcript/raw/reduced-motion/cancel/error/saved transitions and motion frames\n");
        } finally {
            fx(() -> { if (app != null) app.stop(); if (stage != null) stage.hide(); return null; });
            Platform.exit();
        }
    }

    private void size(int width) throws Exception { fx(() -> { stage.setWidth(width); stage.setHeight(width == 1568 ? 1003 : width == 1280 ? 860 : 740); return null; }); }
    private Button button(String selector) { return (Button) scene.lookup(selector); }
    private TextArea text(String selector) { return (TextArea) scene.lookup(selector); }
    private TabPane tabs() { return (TabPane) scene.lookup("#result-tabs"); }
    private void assertNoMotion() throws Exception {
        fx(() -> {
            var council = scene.lookup("#council-stage");
            for (String name : List.of("pulse", "transfer")) {
                var animation = field(council, name, javafx.animation.Animation.class);
                assertTrue(animation == null || animation.getStatus() == javafx.animation.Animation.Status.STOPPED);
            }
            return null;
        });
    }
    private void capture(String name) throws Exception { Thread.sleep(100); fx(() -> { captureNow(name); return null; }); }
    private void captureNow(String name) throws Exception {
        scene.getRoot().applyCss(); scene.getRoot().layout();
        var image = scene.snapshot(null);
        int w = (int) image.getWidth(), h = (int) image.getHeight();
        int[] pixels = new int[w * h];
        image.getPixelReader().getPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), pixels, 0, w);
        BufferedImage output = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        output.setRGB(0, 0, w, h, pixels, 0, w);
        ImageIO.write(output, "png", QA.resolve(name + ".png").toFile()); captures.add(name + ".png");
    }
    private static <T> T field(Object owner, String name, Class<T> type) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return type.cast(field.get(owner));
    }
    private static <T> T fx(Callable<T> action) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> { try { result.complete(action.call()); } catch (Throwable error) { result.completeExceptionally(error); } });
        return result.get(20, TimeUnit.SECONDS);
    }
    private static void await(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.call() && System.nanoTime() < deadline) Thread.sleep(80);
        assertTrue(condition.call(), "native async completion timed out");
    }
}
