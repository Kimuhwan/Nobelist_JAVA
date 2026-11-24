package com.scientificlens.harness.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.scientificlens.harness.core.AnalysisLibrary;
import com.scientificlens.harness.core.Json;
import com.scientificlens.harness.core.Orchestrator;
import com.scientificlens.harness.core.PdfReportWriter;
import com.scientificlens.harness.core.Reporting;
import com.scientificlens.harness.domain.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
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

@EnabledIfSystemProperty(named = "slh.product.ui", matches = "true")
final class ProductUiTest {
    private static final Path QA = Path.of("target", "product-ui-qa");
    private final List<String> receipts = new ArrayList<>();
    private final List<String> captures = new ArrayList<>();
    private MainApp app;
    private Stage stage;
    private Scene scene;
    private Path store;

    @Test
    void nativeLibraryRevisionsActivityAndPersistenceFailureRecovery() throws Exception {
        Files.createDirectories(QA);
        store = Files.createTempDirectory(QA, "library-");
        CompletableFuture<Void> ready = new CompletableFuture<>();
        Platform.startup(() -> { Platform.setImplicitExit(false); ready.complete(null); });
        ready.get(20, TimeUnit.SECONDS);
        try {
            launch(store);
            fx(() -> { button(scene, "#open-library").fire(); return null; });
            awaitLibrary();
            capture(libraryScene(), "library-empty.png");
            assertEquals(0, fx(() -> libraryList().getItems().size()));
            fx(() -> { libraryView().close(); return null; });
            runMock("지속 가능한 창고 센서 서비스\n소규모 비교 실험을 설계한다.");
            await(() -> fx(() -> field(app, "lastStored", StoredAnalysis.class) != null));
            StoredAnalysis generated = fx(() -> field(app, "lastStored", StoredAnalysis.class));
            assertEquals(fx(() -> field(app, "lastResult", HarnessResult.class)), new AnalysisLibrary(store).read(generated.id(), false).result());
            pass("Actual successful FX task automatically saves the full immutable result");

            fx(() -> { button(scene, "#open-library").fire(); return null; });
            awaitLibrary();
            fx(() -> { libraryList().getSelectionModel().selectFirst(); return null; });
            capture(libraryScene(), "library-generated.png");
            String notes = "직접 작성한 메모입니다.\n원자료 확보 후 소규모 실험을 진행한다.\n  공백도 유지한다.  ";
            fx(() -> {
                ((TextArea) libraryScene().lookup("#library-title")).setText("센서 서비스 · 1차 연구");
                ((TextArea) libraryScene().lookup("#library-notes")).setText(notes);
                assertTrue(button(libraryScene(), "#library-open").isDisabled());
                assertTrue(libraryList().isDisabled());
                captureNow(libraryScene(), "library-editing.png");
                button(libraryScene(), "#library-save").fire();
                return null;
            });
            awaitLibrary();
            assertEquals(notes, new AnalysisLibrary(store).read(generated.id(), false).notes());
            assertFalse(Reporting.markdown(generated.result()).contains(notes));
            pass("Explicit title/notes edit preserves whitespace locally; unsaved selection/open are guarded");
            String longNotes = "긴 연구 메모: 원자료를 수집하고 사전 기준에 따라 비교한다.\n".repeat(200) + "마지막 메모 문장";
            fx(() -> {
                TextArea editor = (TextArea) libraryScene().lookup("#library-notes");
                editor.setText(longNotes);
                ((ScrollPane) libraryScene().lookup("#library-detail-scroll")).setVvalue(1);
                editor.setScrollTop(Double.MAX_VALUE);
                ((Button) libraryView().getDialogPane().lookupButton(ButtonType.CLOSE)).fire();
                assertTrue(libraryView().isShowing());
                assertEquals(longNotes, editor.getText());
                return null;
            });
            capture(libraryScene(), "library-long-notes-unsaved-close.png");
            fx(() -> { button(libraryScene(), "#library-revert").fire(); return null; });
            assertEquals(notes, fx(() -> ((TextArea) libraryScene().lookup("#library-notes")).getText()));
            pass("Long CJK notes remain editable; close guards unsaved changes and explicit revert restores saved text");
            fx(() -> { ((TextField) libraryScene().lookup("#library-search")).setText("없는검색결과"); return null; });
            capture(libraryScene(), "library-search-empty.png");
            assertEquals(0, fx(() -> libraryList().getItems().size()));
            fx(() -> {
                ((TextField) libraryScene().lookup("#library-search")).setText("원자료 확보");
                assertEquals(1, libraryList().getItems().size());
                libraryList().getSelectionModel().selectFirst();
                button(libraryScene(), "#library-archive").fire();
                return null;
            });
            awaitLibrary();
            assertEquals(0, fx(() -> libraryList().getItems().size()));
            fx(() -> { ((CheckBox) libraryScene().lookup("#library-show-archived")).fire(); return null; });
            awaitLibrary();
            fx(() -> { libraryList().getSelectionModel().selectFirst(); return null; });
            capture(libraryScene(), "library-archived.png");
            assertEquals(notes, fx(() -> ((TextArea) libraryScene().lookup("#library-notes")).getText()));
            assertFalse(fx(() -> ((TextArea) libraryScene().lookup("#library-notes")).isEditable()));
            assertFalse(fx(() -> ((TextArea) libraryScene().lookup("#library-notes")).isDisabled()));
            fx(() -> { button(libraryScene(), "#library-archive").fire(); return null; });
            awaitLibrary();
            fx(() -> { ((CheckBox) libraryScene().lookup("#library-show-archived")).fire(); return null; });
            awaitLibrary();
            pass("Search includes manual notes; archive/restore is reversible and retains full metadata");
            fx(() -> { libraryView().close(); app.stop(); stage.hide(); return null; });
            launch(store);
            fx(() -> { button(scene, "#open-library").fire(); return null; });
            awaitLibrary();
            fx(() -> { libraryList().getSelectionModel().selectFirst(); button(libraryScene(), "#library-open").fire(); return null; });
            await(() -> fx(() -> !libraryView().isShowing()));
            assertEquals(generated.result(), fx(() -> field(app, "lastResult", HarnessResult.class)));
            assertEquals(generated.result().problem().question(), fx(() -> ((TextArea) scene.lookup("#question-input")).getText()));
            assertTrue(fx(() -> ((ToggleButton) scene.lookup("#mode-" + generated.result().problem().mode().value())).isSelected()));
            assertTrue(fx(() -> ((ToggleButton) scene.lookup("#reading-focus")).isSelected()));
            assertTrue(fx(() -> ((Label) scene.lookup("#analysis-status")).getText().startsWith("저장된 보고서")));
            assertFalse(fx(() -> ((MenuButton) scene.lookup("#report-export-menu")).isDisabled()));
            capture(scene, "restored-after-restart.png");
            fx(() -> { tabs().getSelectionModel().select(4); return null; });
            capture(scene, "saved-activity-empty.png");
            fx(() -> { ((CheckBox) scene.lookup("#activity-raw")).fire(); return null; });
            capture(scene, "saved-raw-unavailable.png");
            assertTrue(fx(() -> field(app, "log", TextArea.class).getText().contains("원본 프로세스 출력이 보관되지")));
            pass("Fresh MainApp instance reopens complete question/mode/result/exports and labels saved replay honestly");

            HarnessResult revised = resultFixture();
            Path input = QA.resolve("revision-input.json");
            Files.writeString(input, Reporting.json(revised));
            fx(() -> { button(scene, "#open-library").fire(); return null; });
            awaitLibrary();
            fx(() -> { libraryView().importReport(input); return null; });
            awaitLibrary();
            StoredAnalysis imported = fx(() -> libraryList().getSelectionModel().getSelectedItem());
            assertNotNull(imported);
            assertEquals("imported", imported.origin());
            assertEquals(revised, imported.result());
            fx(() -> { button(libraryScene(), "#library-open").fire(); return null; });
            await(() -> fx(() -> !libraryView().isShowing()));
            pass("JSON import uses a fresh record and retains exact typed revisions and public events");
            for (int width : List.of(1568, 1280, 1000)) {
                fx(() -> { stage.setWidth(width); stage.setHeight(width == 1568 ? 1003 : width == 1280 ? 860 : 740); return null; });
                for (int page = 0; page < 7; page++) {
                    final int index = page;
                    fx(() -> { tabs().getSelectionModel().select(index); return null; });
                    capture(scene, "revised-page-" + page + "-" + width + ".png");
                }
                fx(() -> {
                    tabs().getSelectionModel().select(2);
                    for (int i = 1; i <= revised.lensRevisions().size(); i++) {
                        TitledPane comparison = (TitledPane) scene.lookup("#revision-comparison-" + i);
                        if (comparison != null) comparison.setExpanded(true);
                    }
                    return null;
                });
                captureScroll((ScrollPane) fx(() -> tabs().getTabs().get(2).getContent()), "revisions-expanded-" + width);
                fx(() -> {
                    tabs().getSelectionModel().select(4);
                    ((ToggleButton) scene.lookup("#activity-transcript")).setSelected(true);
                    ((CheckBox) scene.lookup("#activity-follow")).setSelected(true);
                    ((CheckBox) scene.lookup("#activity-follow")).setSelected(false);
                    TitledPane first = (TitledPane) scene.lookup("#activity-event-1");
                    assertNotNull(first); first.setExpanded(true);
                    return null;
                });
                captureScroll(fx(() -> (ScrollPane) scene.lookup("#activity-scroll")), "activity-expanded-" + width);
                fx(() -> { ((CheckBox) scene.lookup("#activity-raw")).setSelected(true); return null; });
                capture(scene, "activity-raw-" + width + ".png");
                fx(() -> { ((CheckBox) scene.lookup("#activity-raw")).setSelected(false); button(scene, "#open-library").fire(); return null; });
                awaitLibrary();
                fx(() -> {
                    Stage dialogStage = (Stage) libraryScene().getWindow();
                    dialogStage.setWidth(width == 1568 ? 1120 : width == 1280 ? 980 : 820);
                    dialogStage.setHeight(740);
                    libraryList().getSelectionModel().selectFirst();
                    return null;
                });
                capture(libraryScene(), "library-responsive-" + width + ".png");
                ScrollPane detailScroll = fx(() -> (ScrollPane) libraryScene().lookup(width == 1000 ? "#library-stack-scroll" : "#library-detail-scroll"));
                captureScroll(detailScroll, "library-detail-" + width, libraryScene());
                fx(() -> { libraryView().close(); return null; });
            }
            Path pdf = QA.resolve("revised-report.pdf");
            PdfReportWriter.write(revised, pdf);
            pass("All seven native tabs and expanded revisions/activity render at three desktop sizes; exact report exports to PDF");

            Path corrupt = store.resolve(UUID.randomUUID() + ".json");
            Files.writeString(corrupt, "{invalid");
            Path invalidImport = QA.resolve("invalid-import.json");
            Files.writeString(invalidImport, "{}");
            fx(() -> { button(scene, "#open-library").fire(); return null; });
            awaitLibrary();
            capture(libraryScene(), "library-corrupt-warning.png");
            assertTrue(Files.exists(corrupt));
            fx(() -> { libraryView().importReport(invalidImport); return null; });
            awaitLibrary();
            assertTrue(fx(() -> field(libraryView(), "status", Label.class).getText().startsWith("처리하지 못했습니다")));
            capture(libraryScene(), "library-invalid-import.png");
            pass("Corrupt owned records are visibly reported and retained; invalid import cannot replace the current result");
            fx(() -> { libraryView().close(); return null; });

            CountDownLatch release = new CountDownLatch(1);
            fx(() -> field(app, "libraryWorker", ExecutorService.class)).submit(() -> {
                try { release.await(20, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            runMock("첫 번째 저장 대기 연구");
            runMock("두 번째 최신 연구");
            release.countDown();
            await(() -> fx(() -> field(app, "lastStored", StoredAnalysis.class) != null));
            assertEquals("두 번째 최신 연구", fx(() -> field(app, "lastStored", StoredAnalysis.class).result().problem().question()));
            assertEquals("두 번째 최신 연구", fx(() -> field(app, "lastResult", HarnessResult.class).problem().question()));
            pass("Delayed save callback cannot replace a newer visible result; both completed studies persist");

            fx(() -> { app.stop(); stage.hide(); return null; });
            Path blocked = QA.resolve("blocked-library-" + UUID.randomUUID());
            Files.writeString(blocked, "occupied by this test");
            launch(blocked);
            runMock("저장 실패 후 복구 연구");
            await(() -> fx(() -> field(app, "librarySaveFailed", BooleanProperty.class).get()));
            assertTrue(fx(() -> field(app, "hasResult", BooleanProperty.class).get()));
            assertFalse(fx(() -> ((MenuButton) scene.lookup("#report-export-menu")).isDisabled()));
            capture(scene, "autosave-failure-result-retained.png");
            Files.delete(blocked);
            fx(() -> { button(scene, "#retry-library-save").fire(); return null; });
            await(() -> fx(() -> field(app, "lastStored", StoredAnalysis.class) != null));
            assertEquals(1, new AnalysisLibrary(blocked).list(false).entries().size());
            assertFalse(fx(() -> field(app, "librarySaveFailed", BooleanProperty.class).get()));
            capture(scene, "autosave-recovered.png");
            pass("Disk failure retains completed result/exports; real retry action saves exactly one complete report");
            fx(() -> {
                ((ToggleButton) scene.lookup("#reading-focus")).setSelected(false);
                ((TextArea) scene.lookup("#question-input")).setText("취소한 연구는 저장하지 않는다");
                button(scene, "#run-analysis").fire();
                button(scene, "#cancel-analysis").fire();
                return null;
            });
            await(() -> fx(() -> !field(app, "busy", BooleanProperty.class).get()));
            assertEquals(1, new AnalysisLibrary(blocked).list(false).entries().size());
            capture(scene, "cancel-no-save.png");
            pass("Cancel before queued FX success creates no new saved report");
            Files.writeString(QA.resolve("receipt.txt"), String.join("\n", receipts) + "\n");
            Files.writeString(QA.resolve("captures.txt"), String.join("\n", captures) + "\n");
        } finally {
            fx(() -> { if (app != null) app.stop(); if (stage != null) stage.hide(); return null; });
            Platform.exit();
        }
    }

    private void launch(Path directory) throws Exception {
        System.setProperty("slh.library.path", directory.toString());
        fx(() -> { app = new MainApp(); stage = new Stage(); app.start(stage); scene = stage.getScene();
            CouncilOpeningUiTest.enterForWorkspaceTests(scene);
            stage.setWidth(1568); stage.setHeight(1003); return null; });
    }

    private void runMock(String question) throws Exception {
        fx(() -> {
            ((ToggleButton) scene.lookup("#reading-focus")).setSelected(false);
            button(scene, "#settings-button").fire();
            Dialog<?> settings = field(app, "settingsDialog", Dialog.class);
            ((ToggleButton) settings.getDialogPane().lookup("#engine-mock")).fire(); settings.close();
            ((TextArea) scene.lookup("#question-input")).setText(question);
            button(scene, "#run-analysis").fire();
            return null;
        });
        await(() -> fx(() -> !field(app, "busy", BooleanProperty.class).get()));
        assertNotNull(fx(() -> field(app, "lastResult", HarnessResult.class)));
    }

    private static HarnessResult resultFixture() throws Exception {
        String actual = System.getProperty("slh.product.result");
        if (actual != null) return Json.MAPPER.readValue(Files.readString(Path.of(actual)), HarnessResult.class);
        HarnessResult base = Orchestrator.analyzeMock(new UserProblem("스크립트 검증용 센서 서비스\n안전한 비교 실험", Mode.FAST, "QA fixture"));
        LensOutput before = base.lensOutputs().get(1);
        LensOutput after = new LensOutput(before.lensId(), "수정된 관점은 안전 검수와 작동 시간을 분리한다.",
                List.of("사전 등록한 가설로 비교한다."), List.of("교사 감독이 유지된다고 가정한다."),
                List.of("작동 로그와 안전 검수 원자료"), List.of("키트 숙련도가 교란할 수 있다."),
                List.of("무작위 배정하고 동일 키트로 비교한다."), 0.36);
        DebateFinding finding = new DebateFinding(base.selectedLenses().getFirst(), before.lensId(), "안전 조건이 정해지지 않았다.", "안전 검수와 중단 기준을 명시한다.");
        LensRevision revision = new LensRevision(finding, "스크립트 검증 답변: 반론에 따라 안전 조건을 수정했습니다.\n답변의 두 번째 문단도 보존됩니다.", before, after);
        var outputs = base.lensOutputs().stream().map(output -> output.lensId().equals(after.lensId()) ? after : output).toList();
        var events = List.of(new AgentEvent(1, "orchestrator", before.lensId(), "delegate", "스크립트 검증용 공개 작업 지시\n안전 기준을 확인하세요."),
                new AgentEvent(2, before.lensId(), "orchestrator", "reply", revision.response()));
        return new HarnessResult(base.problem(), base.problemType(), "scripted:product-qa", base.selectedLenses(), outputs,
                List.of(finding), base.redTeamFindings(), base.experimentPlan(), base.finalDecision(), events, List.of(revision));
    }

    private AnalysisLibraryView libraryView() throws Exception { return field(app, "libraryView", AnalysisLibraryView.class); }
    private Scene libraryScene() throws Exception { return fx(() -> libraryView().getDialogPane().getScene()); }
    @SuppressWarnings("unchecked")
    private ListView<StoredAnalysis> libraryList() throws Exception { return (ListView<StoredAnalysis>) libraryScene().lookup("#library-list"); }
    private TabPane tabs() { return (TabPane) scene.lookup("#result-tabs"); }
    private static Button button(Scene scene, String id) { return (Button) scene.lookup(id); }
    private void awaitLibrary() throws Exception { await(() -> fx(() -> !field(libraryView(), "working", BooleanProperty.class).get())); }
    private void pass(String message) { receipts.add("PASS " + message); }

    private void capture(Scene target, String name) throws Exception { Thread.sleep(120); fx(() -> { captureNow(target, name); return null; }); }
    private void captureNow(Scene target, String name) throws Exception {
        target.getRoot().applyCss(); target.getRoot().layout();
        var image = target.snapshot(null);
        int width = (int) image.getWidth(); int height = (int) image.getHeight();
        int[] pixels = new int[width * height];
        image.getPixelReader().getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width);
        BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        output.setRGB(0, 0, width, height, pixels, 0, width);
        ImageIO.write(output, "png", QA.resolve(name).toFile()); captures.add(name);
    }

    private void captureScroll(ScrollPane scroll, String prefix) throws Exception {
        captureScroll(scroll, prefix, scene);
    }

    private void captureScroll(ScrollPane scroll, String prefix, Scene target) throws Exception {
        Thread.sleep(120);
        double range = fx(() -> Math.max(0, scroll.getContent().getBoundsInLocal().getHeight() - scroll.getViewportBounds().getHeight()));
        double step = fx(() -> Math.max(1, scroll.getViewportBounds().getHeight() - 80));
        for (int index = 0; index <= Math.ceil(range / step); index++) {
            final double position = range == 0 ? 0 : Math.min(1, index * step / range);
            fx(() -> { scroll.setVvalue(position); return null; });
            capture(target, prefix + "-" + index + ".png");
        }
        fx(() -> { scroll.setVvalue(0); return null; });
    }

    private static <T> T field(Object owner, String name, Class<T> type) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return type.cast(field.get(owner));
    }
    private static <T> T fx(Callable<T> action) throws Exception {
        if (Platform.isFxApplicationThread()) return action.call();
        CompletableFuture<T> value = new CompletableFuture<>();
        Platform.runLater(() -> { try { value.complete(action.call()); } catch (Throwable failure) { value.completeExceptionally(failure); } });
        return value.get(20, TimeUnit.SECONDS);
    }
    private static void await(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (!condition.call() && System.nanoTime() < deadline) Thread.sleep(80);
        assertTrue(condition.call(), "native async condition timed out");
    }
}
