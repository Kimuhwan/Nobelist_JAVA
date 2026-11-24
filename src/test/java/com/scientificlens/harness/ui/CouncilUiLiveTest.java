package com.scientificlens.harness.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.scientificlens.harness.core.Reporting;
import com.scientificlens.harness.core.AnalysisLibrary;
import com.scientificlens.harness.core.Json;
import com.scientificlens.harness.domain.HarnessResult;
import com.scientificlens.harness.llm.CliProvider;
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
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.PixelFormat;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "slh.council.live", matches = "true")
final class CouncilUiLiveTest {
    private static final String REPLAY = System.getProperty("slh.council.replay");
    private static final String MODEL = System.getProperty("slh.council.model", "gpt-6.1-sol");
    private static final Path QA = Path.of(System.getProperty("slh.council.output",
            Path.of("target", REPLAY == null ? "council-qa" : "council-replay-qa").toString()));
    private final List<String> captures = new ArrayList<>();
    private MainApp app;
    private Stage stage;
    private Scene scene;

    @Test
    void realCoordinatorDelegatesDebatesRevisesAndExportsThroughNativeApp() throws Exception {
        Files.createDirectories(QA);
        System.setProperty("slh.library.path", Files.createTempDirectory(QA, "test-library-").toString());
        CompletableFuture<Void> ready = new CompletableFuture<>();
        Platform.startup(() -> { Platform.setImplicitExit(false); ready.complete(null); });
        ready.get(20, TimeUnit.SECONDS);
        try {
            fx(() -> {
                app = new MainApp(); stage = new Stage(); app.start(stage); scene = stage.getScene();
                CouncilOpeningUiTest.enterForWorkspaceTests(scene);
                stage.setWidth(1568); stage.setHeight(1003);
                ((ToggleButton) scene.lookup("#mode-fast")).fire();
                ((Button) scene.lookup("#settings-button")).fire();
                Dialog<?> settings = field("settingsDialog", Dialog.class);
                ((ToggleButton) settings.getDialogPane().lookup("#engine-cli")).fire();
                ComboBox<?> provider = field("cliProvider", ComboBox.class);
                provider.getSelectionModel().select(provider.getItems().indexOf(CliProvider.CODEX));
                selectModel(MODEL);
                settings.close();
                ((TextArea) scene.lookup("#question-input")).setText(System.getProperty("slh.council.question",
                        "중고등학교 과학·로봇 동아리의 아두이노 초보자가 AI 설계 지원을 사용하면 "
                        + "기존 튜토리얼 대비 안전 검수를 통과한 초음파 거리 경고 장치의 제작 시간을 30% 줄일 수 있을까? "
                        + "동일 키트와 교사 감독 조건에서 검증할 첫 실험을 설계해줘."));
                if (REPLAY == null) {
                    ((Button) scene.lookup("#run-analysis")).fire();
                    assertTrue(field("busy", javafx.beans.property.BooleanProperty.class).get());
                    capture("real-loading.png");
                } else {
                    HarnessResult saved = Json.MAPPER.readValue(Files.readString(Path.of(REPLAY)), HarnessResult.class);
                    var library = new AnalysisLibrary(Path.of(System.getProperty("slh.library.path")));
                    app.restoreReport(library.save(saved));
                    assertFalse(scene.lookup("#welcome-view").isVisible());
                    capture("replay-loaded.png");
                }
                return null;
            });
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(25);
            long nextLog = 0;
            while (fx(() -> field("busy", javafx.beans.property.BooleanProperty.class).get())) {
                assertTrue(System.nanoTime() < deadline, "real council completion deadline");
                if (System.nanoTime() >= nextLog) {
                    System.out.println("REAL_COUNCIL_PROGRESS " + fx(() -> ((Label) scene.lookup("#analysis-status")).getText()));
                    fx(() -> { capture("live-phase-" + captures.size() + ".png"); return null; });
                    nextLog = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                }
                Thread.sleep(250);
            }
            HarnessResult result = fx(() -> field("lastResult", HarnessResult.class));
            if (result == null) {
                String error = fx(() -> field("log", TextArea.class).getText());
                Files.writeString(QA.resolve("failure.txt"), error);
                fail("Real native council failed; see " + QA.resolve("failure.txt"));
            }
            Files.writeString(QA.resolve("result.json"), Reporting.json(result));
            Files.writeString(QA.resolve("report.md"), Reporting.markdown(result));
            assertEquals("cli:codex:" + MODEL, result.runtime());
            assertEquals(5, result.selectedLenses().size());
            assertEquals(result.selectedLenses(), result.lensOutputs().stream().map(o -> o.lensId()).toList());
            long replies = result.agentEvents().stream().filter(e -> e.action().equals("reply")).count();
            long challenges = result.agentEvents().stream().filter(e -> e.action().equals("challenge")).count();
            assertTrue(replies >= 1 && replies <= 2);
            assertEquals(challenges, replies);
            assertEquals(result.debateFindings().size(), replies);
            if (REPLAY == null) {
                assertEquals(result.debateFindings().size(), result.lensRevisions().size());
                assertTrue(result.lensRevisions().stream().anyMatch(revision -> !revision.changedFields().isEmpty()));
            }
            assertFalse(result.redTeamFindings().isEmpty());
            assertFalse(result.finalDecision().nextDecision().isBlank());
            Files.writeString(QA.resolve("real-llm-receipt.txt"), (REPLAY == null
                    ? "PASS actual MainApp CLI OAuth workflow\n" : "PASS native replay of saved actual council result; no new LLM calls\n")
                    + "runtime=" + result.runtime() + "\nselected=" + result.selectedLenses()
                    + "\npublicEvents=" + result.agentEvents().size() + "\nrealChallengeReplyExchanges=" + replies + "\n");
            Files.writeString(QA.resolve("revision-receipt.txt"), "typedRevisions=" + result.lensRevisions().size() + "\n"
                    + result.lensRevisions().stream().map(revision -> revision.finding().sourceLensId() + " -> "
                            + revision.finding().targetLensId() + " changed=" + revision.changedFields())
                            .collect(java.util.stream.Collectors.joining("\n")) + "\n");
            System.out.println((REPLAY == null ? "REAL_COUNCIL_COMPLETE" : "COUNCIL_NATIVE_REPLAY_COMPLETE")
                    + " agents=5 exchanges=" + replies + " events=" + result.agentEvents().size());
            fx(() -> { capture("real-summary.png"); return null; });
            fx(() -> { tabs().getSelectionModel().select(4); capture("real-council.png"); return null; });
            for (int width : List.of(1568, 1280, 1000)) {
                fx(() -> { stage.setWidth(width); return null; });
                Thread.sleep(150);
                for (int page = 0; page < 7; page++) {
                    final int index = page;
                    fx(() -> {
                        TabPane tabs = tabs(); tabs.getSelectionModel().select(index);
                        capture("real-page-" + index + "-" + width + ".png");
                        return null;
                    });
                }
                fx(() -> {
                    tabs().getSelectionModel().select(2);
                    TitledPane transcript = (TitledPane) scene.lookup("#agent-transcript");
                    assertNotNull(transcript); transcript.setExpanded(true);
                    for (int i = 1; i <= result.lensRevisions().size(); i++) {
                        TitledPane comparison = (TitledPane) scene.lookup("#revision-comparison-" + i);
                        if (comparison != null) comparison.setExpanded(true);
                    }
                    return null;
                });
                Thread.sleep(200);
                ScrollPane scroll = fx(() -> (ScrollPane) tabs().getTabs().get(2).getContent());
                double range = fx(() -> Math.max(0, scroll.getContent().getBoundsInLocal().getHeight()
                        - scroll.getViewportBounds().getHeight()));
                double step = fx(() -> scroll.getViewportBounds().getHeight() - 80);
                assertTrue(step > 0, "The transcript must have a visible viewport before capturing it");
                for (int screen = 0; screen <= Math.ceil(range / step); screen++) {
                    final double position = range == 0 ? 0 : Math.min(1, screen * step / range);
                    final int number = screen;
                    fx(() -> { scroll.setVvalue(position); capture("transcript-" + width + "-" + number + ".png"); return null; });
                }
                fx(() -> { scroll.setVvalue(0); return null; });
            }
            Path pdf = QA.resolve("real-council-report.pdf").toAbsolutePath();
            fx(() -> { app.exportPdf(pdf); return null; });
            long exportDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
            while (fx(() -> field("pdfExporting", javafx.beans.property.BooleanProperty.class).get())) {
                assertTrue(System.nanoTime() < exportDeadline, "native PDF export deadline");
                Thread.sleep(100);
            }
            fx(() -> {
                DialogPane notice = Window.getWindows().stream().filter(Window::isShowing)
                        .map(Window::getScene).map(Scene::getRoot)
                        .filter(root -> "pdf-export-success".equals(root.getId()))
                        .map(root -> (DialogPane) root).findFirst().orElseThrow();
                assertEquals(pdf.toString(), notice.getContentText());
                ((Button) notice.lookupButton(ButtonType.OK)).fire(); return null;
            });
            try (var document = Loader.loadPDF(pdf.toFile())) {
                String text = new TranscriptBodyStripper().getText(document).replaceAll("\\s+", "");
                for (var event : result.agentEvents()) {
                    assertTrue(text.contains(event.content().replaceAll("\\s+", "")), "event preserved " + event.sequence());
                }
                for (var revision : result.lensRevisions()) {
                    assertTrue(text.contains(revision.response().replaceAll("\\s+", "")), "exact typed response preserved");
                    for (String field : revision.changedFields()) {
                        assertTrue(text.contains(Reporting.lensFieldValue(revision.before(), field).replaceAll("\\s+", "")), "before preserved: " + field);
                        assertTrue(text.contains(Reporting.lensFieldValue(revision.after(), field).replaceAll("\\s+", "")), "after preserved: " + field);
                    }
                }
                var last = document.getDocumentCatalog().getDocumentOutline().getFirstChild();
                int sections = 0;
                while (last != null) { sections++; last = last.getNextSibling(); }
                assertEquals(9, sections);
                Files.writeString(QA.resolve("pdf-receipt.txt"), "PASS actual native async PDF export\n"
                        + "pages=" + document.getNumberOfPages() + "\nbookmarks=9\nfullPublicEventsPreserved=" + result.agentEvents().size() + "\n");
            }
            Files.writeString(QA.resolve("capture-manifest.txt"), String.join("\n", captures) + "\n");
        } finally {
            fx(() -> { Window.getWindows().stream().filter(Window::isShowing).toList().forEach(Window::hide); if (app != null) app.stop(); return null; });
            Platform.exit();
        }
    }

    @SuppressWarnings("unchecked")
    private void selectModel(String model) throws Exception {
        ComboBox<String> combo = (ComboBox<String>) field("cliModel", ComboBox.class);
        combo.setValue(model);
    }

    private TabPane tabs() { return (TabPane) scene.lookup("#result-tabs"); }

    private <T> T field(String name, Class<T> type) throws Exception {
        Field field = MainApp.class.getDeclaredField(name); field.setAccessible(true); return type.cast(field.get(app));
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> { try { result.complete(action.call()); } catch (Throwable error) { result.completeExceptionally(error); } });
        return result.get(30, TimeUnit.SECONDS);
    }

    private void capture(String name) throws Exception {
        scene.getRoot().applyCss(); scene.getRoot().layout();
        var parameters = new javafx.scene.SnapshotParameters(); parameters.setFill(javafx.scene.paint.Color.WHITE);
        parameters.setViewport(new javafx.geometry.Rectangle2D(0, 0, Math.floor(scene.getWidth()), Math.floor(scene.getHeight())));
        var image = scene.getRoot().snapshot(parameters, null);
        int width = (int) image.getWidth(), height = (int) image.getHeight();
        BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = new int[width * height];
        image.getPixelReader().getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width);
        output.setRGB(0, 0, width, height, pixels, 0, width);
        assertTrue(ImageIO.write(output, "png", QA.resolve(name).toFile()));
        captures.add(name + " | " + width + "x" + height);
    }

    private static final class TranscriptBodyStripper extends PDFTextStripper {
        TranscriptBodyStripper() throws java.io.IOException {}

        @Override
        protected void processTextPosition(TextPosition text) {
            float baseline = text.getYDirAdj();
            if (baseline > 110 && baseline < PDRectangle.A4.getHeight() - 65) {
                super.processTextPosition(text);
            }
        }
    }
}
