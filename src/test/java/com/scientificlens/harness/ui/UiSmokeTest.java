package com.scientificlens.harness.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.scientificlens.harness.core.Orchestrator;
import com.scientificlens.harness.core.Reporting;
import com.scientificlens.harness.domain.ExperimentPlan;
import com.scientificlens.harness.domain.FinalDecision;
import com.scientificlens.harness.domain.HarnessResult;
import com.scientificlens.harness.domain.Mode;
import com.scientificlens.harness.domain.RedTeamFinding;
import com.scientificlens.harness.domain.Severity;
import com.scientificlens.harness.domain.UserProblem;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.PixelFormat;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Opt-in native UI integration. Run with -Dslh.ui.test=true on a desktop. */
@EnabledIfSystemProperty(named = "slh.ui.test", matches = "true")
final class UiSmokeTest {
    private static final Path EVIDENCE = Path.of("target", "report-ui-qa");
    private final List<String> receipts = new ArrayList<>();
    private final List<String> captures = new ArrayList<>();
    private MainApp app;
    private Stage stage;
    private Scene scene;

    @Test
    void nativeWorkflowPreservesAnalysisNavigationAndResponsiveReports() throws Exception {
        Files.createDirectories(EVIDENCE);
        System.setProperty("slh.library.path", Files.createTempDirectory(EVIDENCE, "test-library-").toString());
        CompletableFuture<Void> initialized = new CompletableFuture<>();
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            initialized.complete(null);
        });
        initialized.get(20, TimeUnit.SECONDS);
        try {
            fx(() -> {
                app = new MainApp();
                stage = new Stage();
                app.start(stage);
                scene = stage.getScene();
                CouncilOpeningUiTest.enterForWorkspaceTests(scene);
                return null;
            });
            size(1568, 1003);
            fx(() -> { button("#welcome-workspace").fire(); return null; });
            assertTrue(fx(() -> button("#run-analysis").isDisabled()));
            assertTrue(fx(() -> menu("#export-menu").isDisabled()));
            pass("Empty input disables analysis and export");
            capture(scene, "empty-1568.png");

            WorkflowBar pendingWorkflow = fx(() -> field("workflow", WorkflowBar.class));
            fx(() -> { pendingWorkflow.begin(0); return null; });
            List<Integer> firstCoordinatorProgress = new ArrayList<>();
            var pausedCouncil = (com.scientificlens.harness.runtime.CouncilRuntime) java.lang.reflect.Proxy.newProxyInstance(
                    com.scientificlens.harness.runtime.CouncilRuntime.class.getClassLoader(),
                    new Class<?>[]{com.scientificlens.harness.runtime.CouncilRuntime.class},
                    (proxy, method, arguments) -> {
                        if (method.getName().equals("nextAction")) {
                            throw new java.util.concurrent.CancellationException("Stop before selecting lenses");
                        }
                        throw new AssertionError("Unexpected runtime call: " + method.getName());
                    });
            assertThrows(java.util.concurrent.CancellationException.class, () ->
                    new com.scientificlens.harness.core.AgentOrchestrator(com.scientificlens.harness.core.LensRegistry.load()).run(
                            new UserProblem("렌즈 선택 전 진행 표시 검증", Mode.FAST, ""), pausedCouncil,
                            (step, total, message) -> {
                                firstCoordinatorProgress.add(step);
                                Platform.runLater(() -> pendingWorkflow.update(step));
                            }, com.scientificlens.harness.runtime.StageContext.NONE));
            assertEquals(List.of(0), firstCoordinatorProgress);
            fx(() -> {
                assertTrue(scene.lookup("#workflow-0").getAccessibleText().contains("진행 중"));
                for (int i = 0; i < 4; i++) {
                    assertFalse(scene.lookup("#workflow-" + i).getAccessibleText().contains("완료"));
                    assertFalse(scene.lookup("#workflow-" + i).getStyleClass().contains("workflow-done"));
                }
                assertTrue(scene.lookup("#workflow-1").getAccessibleText().contains("대기"));
                captureNow(scene, "coordinator-preselection.png");
                pendingWorkflow.stop();
                assertFalse(scene.lookup("#workflow-1").getAccessibleText().contains("완료"));
                pendingWorkflow.begin(5);
                pendingWorkflow.update(0);
                assertTrue(scene.lookup("#workflow-0").getAccessibleText().contains("완료"));
                assertTrue(scene.lookup("#workflow-1").getAccessibleText().contains("진행 중"));
                assertFalse(scene.lookup("#workflow-2").getAccessibleText().contains("완료"));
                captureNow(scene, "coordinator-selected.png");
                pendingWorkflow.stop();
                return null;
            });
            pass("Actual first coordinator progress stays in pending lens selection with no false completions; selected lenses begin their own phase (cancelled runtime fixture, no LLM call)");

            String longQuestion = "긴 한국어 질문과 영문 descenders gypQj를 잘리지 않게 읽고 편집합니다. ".repeat(30);
            fx(() -> { question().setText(longQuestion); return null; });
            for (int width : List.of(1568, 1280, 1000)) {
                size(width, width == 1568 ? 1003 : width == 1280 ? 860 : 740);
                fx(() -> {
                    scene.getRoot().applyCss(); scene.getRoot().layout();
                    javafx.scene.text.Text text = question().lookupAll(".text").stream()
                            .filter(node -> node instanceof javafx.scene.text.Text candidate && candidate.getText().equals(longQuestion))
                            .map(node -> (javafx.scene.text.Text) node).findFirst().orElseThrow();
                    Node viewport = question().lookup(".viewport");
                    var visible = viewport.localToScene(viewport.getLayoutBounds());
                    var glyphs = text.localToScene(text.getLayoutBounds());
                    assertTrue(glyphs.getMinY() >= visible.getMinY() - 1 && glyphs.getMaxY() <= visible.getMaxY() + 1,
                            "Collapsed input must contain full native Korean/Latin glyph height at " + width);
                    assertEquals(longQuestion, question().getText());
                    captureNow(scene, "question-long-visible-" + width + ".png");
                    return null;
                });
            }
            size(1568, 1003);
            fx(() -> { question().clear(); return null; });
            pass("Long collapsed question retains complete Korean/Latin glyph height at all three viewports and preserves exact editable text");

            for (Mode mode : Mode.values()) {
                fx(() -> {
                    ToggleButton toggle = (ToggleButton) scene.lookup("#mode-" + mode.value());
                    toggle.fire();
                    assertTrue(toggle.isSelected());
                    toggle.fire();
                    assertTrue(toggle.isSelected(), "Keyboard/fire activation must retain one selected mode");
                    return null;
                });
            }
            pass("All three modes remain selected on repeated activation");
            fx(() -> { ((ToggleButton) scene.lookup("#mode-research")).fire(); return null; });
            openSettings();
            capture(settings().getDialogPane().getScene(), "settings-cli.png");
            assertNotNull(fx(() -> settings().getDialogPane().lookup("#domain-hint")));
            assertNotNull(fx(() -> settings().getDialogPane().lookup("#engine-local")));
            pass("Settings retain CLI provider/model and optional domain controls");
            fx(() -> {
                ((ToggleButton) settings().getDialogPane().lookup("#engine-local")).fire();
                return null;
            });
            capture(settings().getDialogPane().getScene(), "settings-local.png");
            fx(() -> {
                ((ToggleButton) settings().getDialogPane().lookup("#engine-mock")).fire();
                return null;
            });
            capture(settings().getDialogPane().getScene(), "settings-mock.png");
            fx(() -> { settings().close(); return null; });
            pass("All three engine views remain accessible");

            String multiline = "아두이노 AI 설계 서비스\n초보자가 안전하게 제작하도록 돕는 아이디어";
            fx(() -> {
                question().setText(multiline);
                Platform.runLater(() -> {
                    Window editor = Window.getWindows().stream()
                            .filter(window -> window.isShowing() && window != stage)
                            .filter(window -> window.getScene().lookup("#expanded-question") != null)
                            .findFirst().orElseThrow();
                    TextArea text = (TextArea) editor.getScene().lookup("#expanded-question");
                    assertEquals(multiline, text.getText());
                    text.appendText("\n검증 질문을 추가합니다.");
                    ((Button) ((DialogPane) editor.getScene().getRoot()).lookupButton(ButtonType.OK)).fire();
                });
                button("#expand-question").fire();
                assertEquals(multiline + "\n검증 질문을 추가합니다.", question().getText());
                return null;
            });
            pass("Expanded editor preserves Korean multiline input");

            fx(() -> {
                button("#run-analysis").fire();
                assertTrue(button("#run-analysis").isDisabled());
                assertTrue(question().isDisabled());
                assertFalse(button("#cancel-analysis").isDisabled());
                captureNow(scene, "loading.png");
                return null;
            });
            await(() -> status().startsWith("완료"), "mock analysis completes");
            pass("Real asynchronous Mock analysis completes and unlocks controls");
            assertFalse(fx(() -> menu("#export-menu").isDisabled()));
            assertEquals(3, fx(() -> menu("#export-menu").getItems().size()));
            assertEquals("다시 분석", fx(() -> button("#run-analysis").getText()));
            assertEquals(0, fx(() -> tabs().getSelectionModel().getSelectedIndex()));
            HarnessResult actual = fx(() -> field("lastResult", HarnessResult.class));
            assertEquals(multiline + "\n검증 질문을 추가합니다.", actual.problem().question());
            for (int i = 0; i < 4; i++) {
                final int index = i;
                assertTrue(fx(() -> scene.lookup("#workflow-" + index).getAccessibleText().contains("완료")));
            }
            assertEquals(Reporting.markdown(actual), fx(() -> field("results", ResultView.class).markdownArea().getText()));
            assertEquals(Reporting.json(actual).replace("\r\n", "\n"), fx(() -> field("results", ResultView.class).jsonArea().getText()));
            fx(() -> {
                assertNoQuotedLineBreak(tabs().getTabs().getFirst().getContent(), actual.problem().question());
                return null;
            });
            pass("Multiline input is retained in raw reports and flows naturally in display prose");
            pass("Result, workflow completion, export menu and raw reports use actual analysis data");
            capture(scene, "actual-mock.png");
            assertTrue(fx(() -> reading().isSelected()));
            assertFalse(fx(() -> scene.lookup("#analysis-settings-region").isVisible()));
            assertTrue(fx(() -> menu("#report-export-menu").isVisible()));
            assertFalse(fx(() -> menu("#report-export-menu").isDisabled()));
            fx(() -> {
                MenuButton export = menu("#report-export-menu");
                javafx.scene.control.Label label = (javafx.scene.control.Label) export.lookup(".label");
                assertTrue(label.getHeight() >= label.prefHeight(-1) - 1,
                        "PDF action label must have its full native preferred height");
                export.requestFocus();
                captureNow(scene, "pdf-action-focused.png");
                export.show();
                captureNow(export.getItems().getFirst().getParentPopup().getScene(), "export-options.png");
                export.hide();
                return null;
            });
            fx(() -> { reading().fire(); return null; });
            assertTrue(fx(() -> scene.lookup("#analysis-settings-region").isVisible()));
            capture(scene, "setup-restored.png");
            pass("Completed analysis enters focused reading and restores editable settings on demand");
            for (int i = 0; i < 4; i++) {
                final int index = i;
                assertEquals(88, fx(() -> ((Button) scene.lookup("#workflow-" + index)).getHeight()), 1,
                        "Completed workflow stages must keep the reference height after asynchronous analysis");
            }

            for (int i = 0; i < 4; i++) {
                final int page = i;
                fx(() -> { button("#workflow-" + page).fire(); return null; });
                assertEquals(page, fx(() -> tabs().getSelectionModel().getSelectedIndex()));
            }
            pass("Every workflow button opens its corresponding real detail page");
            fx(() -> { button("#show-log").fire(); return null; });
            assertEquals(4, fx(() -> tabs().getSelectionModel().getSelectedIndex()));
            pass("Live log remains reachable");
            fx(() -> {
                Button maximize = (Button) scene.lookup(".win-max");
                maximize.fire();
                assertTrue(stage.isMaximized());
                maximize.fire();
                assertFalse(stage.isMaximized());
                ((Button) scene.lookup(".win-min")).fire();
                assertTrue(stage.isIconified());
                stage.setIconified(false);
                return null;
            });
            size(1568, 1003);
            pass("Native minimize and maximize/restore controls work");

            HarnessResult fixture = fixture(false);
            fx(() -> {
                question().setText(fixture.problem().question());
                field("results", ResultView.class).show(fixture);
                setField("lastResult", fixture);
                reading().setSelected(true);
                tabs().getSelectionModel().select(0);
                return null;
            });
            size(1568, 1003);
            for (int page = 0; page < 7; page++) {
                final int index = page;
                fx(() -> { tabs().getSelectionModel().select(index); return null; });
                capture(scene, "page-" + page + "-1568.png");
                if (page == 1) expandDisclosures();
                if (page >= 1 && page <= 3) captureScrollCoverage("detail-" + page + "-1568");
                if (page == 2) {
                    hoverGraphNode(0);
                    capture(scene, "debate-hover.png");
                }
            }
            fx(() -> { tabs().getSelectionModel().select(0); return null; });
            size(1280, 860);
            capture(scene, "summary-1280.png");
            captureSummarySections("summary-1280");
            expandDisclosures();
            fx(() -> { ((ScrollPane) tabs().getTabs().getFirst().getContent()).setVvalue(1); return null; });
            capture(scene, "provenance-expanded-1280.png");
            fx(() -> { ((ScrollPane) tabs().getTabs().getFirst().getContent()).setVvalue(0); return null; });
            size(1000, 740);
            for (int page = 0; page < 7; page++) {
                final int index = page;
                fx(() -> { tabs().getSelectionModel().select(index); return null; });
                capture(scene, "page-" + page + "-1000.png");
                if (page >= 1 && page <= 3) captureScrollCoverage("detail-" + page + "-1000");
                if (page == 2) {
                    assertFalse(fx(() -> scene.lookup(".debate-tooltip").isVisible()),
                            "Resizing must dismiss the previous hover overlay");
                    hoverGraphNode(0);
                    capture(scene, "debate-hover-1000.png");
                }
            }
            fx(() -> {
                reading().fire();
                scene.getRoot().applyCss(); scene.getRoot().layout();
                return null;
            });
            assertAll("main controls stay within the minimum supported window",
                    () -> assertTrue(fx(() -> scene.lookup("#run-analysis").localToScene(
                            scene.lookup("#run-analysis").getBoundsInLocal()).getMaxX() <= scene.getWidth()), "Run action remains inside the laid-out scene"),
                    () -> assertTrue(fx(() -> scene.lookup("#workflow-3").localToScene(
                            scene.lookup("#workflow-3").getBoundsInLocal()).getMaxX() <= scene.getWidth()), "Final workflow control remains inside the laid-out scene"));
            pass("All seven pages render at reference and minimum desktop widths");
            fx(() -> { reading().fire(); tabs().getSelectionModel().select(0); return null; });
            assertTrue(fx(() -> menu("#report-export-menu").localToScene(
                    menu("#report-export-menu").getBoundsInLocal()).getMaxX() <= scene.getWidth()));
            captureSummarySections("summary-1000");
            exportPdfAndObserve(fixture);
            fx(() -> {
                MenuButton menu = menu("#view-menu");
                for (int i = 0; i < menu.getItems().size(); i++) {
                    menu.getItems().get(i).fire();
                    assertEquals(i, tabs().getSelectionModel().getSelectedIndex());
                }
                return null;
            });
            pass("Detail menu navigates all seven pages");

            fx(() -> {
                field("results", ResultView.class).show(fixture(true));
                tabs().getSelectionModel().select(0);
                return null;
            });
            capture(scene, "long-korean-top.png");
            ScrollPane summary = fx(() -> (ScrollPane) tabs().getTabs().getFirst().getContent());
            assertTrue(fx(() -> summary.getContent().getBoundsInLocal().getHeight()
                    > summary.getViewportBounds().getHeight()));
            fx(() -> {
                assertWrappedReportLabels(summary.getContent());
                summary.setVvalue(1);
                return null;
            });
            capture(scene, "long-korean-bottom.png");
            pass("Long Korean report wraps, grows vertically and scrolls through every decision field");

            fx(() -> {
                field("results", ResultView.class).show(actual);
                tabs().getSelectionModel().select(0);
                summary.setVvalue(0);
                button("#run-analysis").fire();
                assertFalse(reading().isSelected());
                assertFalse(scene.lookup("#analysis-settings-region").isVisible());
                assertTrue(reading().isDisabled());
                field("worker", java.util.concurrent.ExecutorService.class).submit(() -> {}).get(20, TimeUnit.SECONDS);
                assertTrue(field("busy", javafx.beans.property.BooleanProperty.class).get());
                assertFalse(field("pdfExporting", javafx.beans.property.BooleanProperty.class).get());
                button("#cancel-analysis").fire();
                assertTrue(field("cancelRequested", java.util.concurrent.atomic.AtomicBoolean.class).get());
                return null;
            });
            await(() -> status().equals("취소됨"), "cooperative cancellation, observed status=" + status());
            assertTrue(fx(() -> menu("#export-menu").isDisabled()));
            assertFalse(fx(() -> button("#run-analysis").isDisabled()));
            capture(scene, "cancelled.png");
            pass("Cancellation stops the worker, leaves no stale export and allows retry");

            fx(() -> { button("#run-analysis").fire(); return null; });
            await(() -> status().startsWith("완료"), "retry completes");
            assertFalse(fx(() -> menu("#export-menu").isDisabled()));
            pass("Repeated analysis after cancellation completes");

            openSettings();
            Path originalPath = fx(() -> field("modelPath", Path.class));
            fx(() -> {
                setField("modelPath", EVIDENCE.resolve("nonexistent-test-model.gguf"));
                ((ToggleButton) settings().getDialogPane().lookup("#engine-local")).fire();
                settings().close();
                Platform.runLater(() -> {
                    Window error = Window.getWindows().stream()
                            .filter(window -> window.isShowing() && window != stage)
                            .filter(window -> window.getScene().getRoot() instanceof DialogPane)
                            .findFirst().orElseThrow();
                    DialogPane pane = (DialogPane) error.getScene().getRoot();
                    assertEquals("모델 없음", pane.getHeaderText());
                    try { captureNow(error.getScene(), "missing-model.png"); }
                    catch (Exception ex) { throw new RuntimeException(ex); }
                    ((Button) pane.lookupButton(ButtonType.OK)).fire();
                });
                button("#run-analysis").fire();
                setField("modelPath", originalPath);
                return null;
            });
            assertFalse(fx(() -> button("#run-analysis").isDisabled()));
            pass("Missing local model shows an owned error dialog without starting analysis");

            fx(() -> { button("#clear-question").fire(); return null; });
            assertEquals("", fx(() -> question().getText()));
            assertTrue(fx(() -> button("#run-analysis").isDisabled()));
            pass("Clear action returns input to the disabled empty state");
            Files.writeString(EVIDENCE.resolve("functional-receipts.txt"), String.join("\n", receipts) + "\n");
            Files.writeString(EVIDENCE.resolve("capture-manifest.txt"), String.join("\n", captures) + "\n");
        } finally {
            fx(() -> {
                if (app != null) { app.stop(); }
                if (stage != null) { stage.hide(); }
                Window.getWindows().stream().filter(Window::isShowing).toList().forEach(Window::hide);
                Platform.exit();
                return null;
            });
        }
    }

    private ToggleButton reading() { return (ToggleButton) scene.lookup("#reading-focus"); }

    private void expandDisclosures() throws Exception {
        fx(() -> {
            Node content = tabs().getSelectionModel().getSelectedItem().getContent();
            for (Node node : content.lookupAll(".titled-pane")) {
                ((javafx.scene.control.TitledPane) node).setExpanded(true);
            }
            scene.getRoot().applyCss(); scene.getRoot().layout();
            return null;
        });
    }

    private void captureScrollCoverage(String prefix) throws Exception {
        ScrollPane scroll = fx(() -> (ScrollPane) tabs().getSelectionModel().getSelectedItem().getContent());
        double range = fx(() -> Math.max(0, scroll.getContent().getBoundsInLocal().getHeight()
                - scroll.getViewportBounds().getHeight()));
        double step = fx(() -> Math.max(1, scroll.getViewportBounds().getHeight() - 80));
        int screens = (int) Math.ceil(range / step);
        for (int screen = 0; screen <= screens; screen++) {
            final double position = range == 0 ? 0 : Math.min(1, screen * step / range);
            fx(() -> { scroll.setVvalue(position); return null; });
            capture(scene, prefix + "-scroll-" + screen + ".png");
        }
        fx(() -> { scroll.setVvalue(0); return null; });
    }

    private void captureSummarySections(String prefix) throws Exception {
        for (int step = 0; step <= 3; step++) {
            final double position = step / 3.0;
            fx(() -> {
                ((ScrollPane) tabs().getTabs().getFirst().getContent()).setVvalue(position);
                return null;
            });
            capture(scene, prefix + "-section-" + step + ".png");
        }
        fx(() -> { ((ScrollPane) tabs().getTabs().getFirst().getContent()).setVvalue(0); return null; });
    }

    private void exportPdfAndObserve(HarnessResult expected) throws Exception {
        Path output = EVIDENCE.resolve("ui-exported-report.pdf").toAbsolutePath();
        fx(() -> {
            app.exportPdf(output);
            assertTrue(menu("#report-export-menu").isDisabled());
            assertTrue(field("pdfExporting", javafx.beans.property.BooleanProperty.class).get());
            captureNow(scene, "pdf-generating.png");
            return null;
        });
        await(() -> {
            try { return fx(() -> pdfNotice() != null); }
            catch (Exception error) { throw new RuntimeException(error); }
        }, "native background PDF completes");
        fx(() -> {
            DialogPane pane = pdfNotice();
            assertEquals("보고서를 PDF로 저장했습니다.", pane.getHeaderText());
            assertEquals(output.toString(), pane.getContentText());
            captureNow(pane.getScene(), "pdf-saved.png");
            ((Button) pane.lookupButton(ButtonType.OK)).fire();
            assertFalse(field("pdfExporting", javafx.beans.property.BooleanProperty.class).get());
            assertFalse(menu("#report-export-menu").isDisabled());
            return null;
        });
        try (var document = Loader.loadPDF(output.toFile())) {
            String content = new PDFTextStripper().getText(document).replaceAll("\\s+", "");
            assertTrue(content.contains(expected.problem().question().replaceAll("\\s+", "")));
            assertTrue(content.contains(expected.finalDecision().coreHypothesis().replaceAll("\\s+", "")));
            assertTrue(content.contains(expected.finalDecision().nextDecision().replaceAll("\\s+", "")));
        }
        pass("Real native export runs off the UI thread, saves searchable Korean PDF and displays its destination");
        Path failurePath = EVIDENCE.resolve("absent-parent").resolve("failed.pdf").toAbsolutePath();
        fx(() -> { app.exportPdf(failurePath); return null; });
        await(() -> {
            try {
                return fx(() -> Window.getWindows().stream()
                        .filter(window -> window.isShowing() && window != stage)
                        .filter(window -> window.getScene().getRoot() instanceof DialogPane)
                        .anyMatch(window -> "PDF 내보내기 실패".equals(
                                ((DialogPane) window.getScene().getRoot()).getHeaderText())));
            } catch (Exception error) { throw new RuntimeException(error); }
        }, "native export failure dialog appears");
        fx(() -> {
            DialogPane pane = Window.getWindows().stream()
                    .filter(window -> window.isShowing() && window != stage)
                    .map(Window::getScene).map(Scene::getRoot)
                    .filter(root -> root instanceof DialogPane)
                    .map(root -> (DialogPane) root)
                    .filter(dialog -> "PDF 내보내기 실패".equals(dialog.getHeaderText()))
                    .findFirst().orElseThrow();
            captureNow(pane.getScene(), "pdf-error.png");
            ((Button) pane.lookupButton(ButtonType.OK)).fire();
            return null;
        });
        await(() -> {
            try { return fx(() -> !field("pdfExporting", javafx.beans.property.BooleanProperty.class).get()); }
            catch (Exception error) { throw new RuntimeException(error); }
        }, "export failure restores controls");
        assertFalse(Files.exists(failurePath));
        assertFalse(fx(() -> menu("#report-export-menu").isDisabled()));
        pass("PDF write errors surface in an owned dialog and permit another export");
    }

    private DialogPane pdfNotice() {
        return Window.getWindows().stream().filter(Window::isShowing)
                .map(Window::getScene).filter(java.util.Objects::nonNull)
                .map(Scene::getRoot).filter(root -> "pdf-export-success".equals(root.getId()))
                .map(root -> (DialogPane) root).findFirst().orElse(null);
    }

    private static HarnessResult fixture(boolean longText) {
        UserProblem problem = new UserProblem("아두이노 AI 설계 서비스", Mode.RESEARCH, "교육 · 제작");
        HarnessResult base = Orchestrator.analyzeMock(problem);
        String hypothesis = "AI 설계 지원이 초보자의 제작 시간을 30% 줄이는가?";
        String assumption = "초보자가 생성된 배선과 코드를 이해하고 안전하게 실행할 수 있다.";
        String experiment = "중고등학교 과학·로봇 동아리의 아두이노 초보자 60명을 두 그룹으로 무작위 배정합니다. 동일 키트를 사용해 초음파 거리 경고 장치를 제작하고, 작동 검증과 안전 점검까지 걸린 시간을 비교합니다.";
        String issue = "오배선과 잘못된 부품 추천 위험";
        if (longText) {
            hypothesis = (hypothesis + " 긴 한국어 가설도 내용 누락 없이 검토해야 합니다. ").repeat(12);
            assumption = (assumption + " 이해 여부와 실제 안전 수칙 준수 여부를 관찰합니다. ").repeat(16);
            experiment = (experiment + " ").repeat(16);
            issue = (issue + " · 출력의 검증 절차와 안전 안내가 필요합니다. ").repeat(12);
        }
        List<String> data = List.of("완료 시간", "작동 성공률", "안전 검수");
        FinalDecision decision = new FinalDecision(hypothesis, assumption, data, experiment,
                "시간 30% 단축, 안전 기준 충족", "효과 부족 또는 안전 문제 발생",
                "검증 후 파일럿 확대 여부 결정",
                "동일 키트를 사용하는 중고등학교 과학·로봇 동아리에서 시작합니다. 교사의 검수 절차를 거쳐 방과후 수업으로 확장합니다.");
        ExperimentPlan plan = new ExperimentPlan(hypothesis, "완료 시간 · 작동 성공률 · 안전 점검",
                data, "기존 방식 그룹 30명 · 동일 과제", "AI 지원 그룹 30명 · 동일 과제",
                "2주", decision.successCriteria(), decision.failureCriteria(), List.of(issue));
        return new HarnessResult(problem, base.problemType(), "Mock · QA 예시 데이터", base.selectedLenses(),
                base.lensOutputs(), base.debateFindings(),
                List.of(new RedTeamFinding(issue, Severity.MEDIUM,
                        "부품 호환성을 확인하고 교사가 배선도를 사전 검토합니다. 제작 후에는 출력 검증과 안전 점검을 반드시 수행합니다.")),
                plan, decision);
    }

    private void hoverGraphNode(int index) throws Exception {
        fx(() -> {
            javafx.scene.canvas.Canvas graph = scene.getRoot().lookupAll("*").stream()
                    .filter(node -> node instanceof javafx.scene.canvas.Canvas)
                    .map(node -> (javafx.scene.canvas.Canvas) node).findFirst().orElseThrow();
            Object graphView = graph.getParent().getParent();
            Field nodes = DebateGraphView.class.getDeclaredField("nodes");
            nodes.setAccessible(true);
            Object node = ((List<?>) nodes.get(graphView)).get(index);
            var cx = node.getClass().getDeclaredMethod("cx");
            var cy = node.getClass().getDeclaredMethod("cy");
            cx.setAccessible(true); cy.setAccessible(true);
            double x = (double) cx.invoke(node), y = (double) cy.invoke(node);
            javafx.scene.input.MouseEvent move = new javafx.scene.input.MouseEvent(graph, graph,
                    javafx.scene.input.MouseEvent.MOUSE_MOVED, x, y, x, y,
                    javafx.scene.input.MouseButton.NONE, 0,
                    false, false, false, false, false, false, false, false, false, false,
                    new javafx.scene.input.PickResult(graph, new javafx.geometry.Point3D(x, y, 0), 0));
            graph.fireEvent(move.copyFor(graph, graph));
            scene.getRoot().applyCss(); scene.getRoot().layout();
            Node tooltip = scene.lookup(".debate-tooltip");
            assertTrue(tooltip.isVisible(), "Hover must show the real debate tooltip");
            ScrollPane scroll = (ScrollPane) tabs().getTabs().get(2).getContent();
            Node viewport = scroll.lookup(".viewport");
            var visible = viewport.localToScene(viewport.getLayoutBounds());
            var bounds = tooltip.localToScene(tooltip.getLayoutBounds());
            assertTrue(bounds.getMinY() >= visible.getMinY() - 1
                    && bounds.getMaxY() <= visible.getMaxY() + 1,
                    "Hover content must fit the visible report viewport: " + bounds + " in " + visible);
            assertTrue(bounds.getMinX() >= visible.getMinX() - 1
                    && bounds.getMaxX() <= visible.getMaxX() + 1);
            return null;
        });
        pass("Graph hover stays within the visible viewport at " + (int) scene.getWidth() + "px");
    }

    private static void assertNoQuotedLineBreak(Node node, String originalQuestion) {
        if (node instanceof Label label) {
            assertFalse(label.getText().contains(originalQuestion),
                    "Question line separators must not fragment generated report prose");
        }
        if (node instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> assertNoQuotedLineBreak(child, originalQuestion));
        }
    }

    private void openSettings() throws Exception {
        fx(() -> { button("#settings-button").fire(); return null; });
        await(() -> {
            try { return fx(() -> settings().isShowing()); }
            catch (Exception error) { throw new RuntimeException(error); }
        }, "settings opens");
    }

    private Dialog<?> settings() throws Exception {
        return field("settingsDialog", Dialog.class);
    }

    private <T> T field(String name, Class<T> type) throws Exception {
        Field field = MainApp.class.getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(app));
    }

    private void setField(String name, Object value) throws Exception {
        Field field = MainApp.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(app, value);
    }

    private Button button(String id) { return (Button) scene.lookup(id); }
    private MenuButton menu(String id) { return (MenuButton) scene.lookup(id); }
    private TextArea question() { return (TextArea) scene.lookup("#question-input"); }
    private TabPane tabs() { return (TabPane) scene.lookup("#result-tabs"); }
    private String status() {
        try { return fx(() -> ((Label) scene.lookup("#analysis-status")).getText()); }
        catch (Exception error) { throw new RuntimeException(error); }
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        if (Platform.isFxApplicationThread()) { return action.call(); }
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try { result.complete(action.call()); }
            catch (Throwable error) { result.completeExceptionally(error); }
        });
        return result.get(30, TimeUnit.SECONDS);
    }

    private static void await(BooleanSupplier condition, String name) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean() && System.nanoTime() < end) { Thread.sleep(100); }
        assertTrue(condition.getAsBoolean(), name);
    }

    private void size(int width, int height) throws Exception {
        fx(() -> { stage.setWidth(width); stage.setHeight(height); return null; });
        Thread.sleep(150);
        fx(() -> { scene.getRoot().applyCss(); scene.getRoot().layout(); return null; });
    }

    private void capture(Scene source, String name) throws Exception {
        Thread.sleep(120);
        fx(() -> { captureNow(source, name); return null; });
    }

    private void captureNow(Scene source, String name) throws Exception {
        source.getRoot().applyCss(); source.getRoot().layout();
        javafx.scene.SnapshotParameters parameters = new javafx.scene.SnapshotParameters();
        parameters.setFill(javafx.scene.paint.Color.WHITE);
        parameters.setViewport(new javafx.geometry.Rectangle2D(
                0, 0, Math.floor(source.getWidth()), Math.floor(source.getHeight())));
        var image = source.getRoot().snapshot(parameters, null);
        int width = (int) image.getWidth(), height = (int) image.getHeight();
        BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = new int[width * height];
        image.getPixelReader().getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width);
        output.setRGB(0, 0, width, height, pixels, 0, width);
        assertTrue(ImageIO.write(output, "png", EVIDENCE.resolve(name).toFile()));
        captures.add(name + " | " + width + "x" + height);
    }

    private static void assertWrappedReportLabels(Node node) {
        if (node instanceof Label label && label.getStyleClass().stream().anyMatch(style ->
                List.of("workbench-hypothesis", "workbench-copy", "experiment-value",
                        "evaluation-value", "quote-value").contains(style))) {
            assertTrue(label.isWrapText());
            assertTrue(label.getWidth() <= label.getParent().getLayoutBounds().getWidth() + 1,
                    "Report label overflows its native parent: " + label.getText().substring(0, Math.min(30, label.getText().length())));
        }
        if (node instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(UiSmokeTest::assertWrappedReportLabels);
        }
    }

    private void pass(String receipt) { receipts.add("PASS | " + receipt); }
}
