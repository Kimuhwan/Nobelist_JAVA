package com.scientificlens.harness.ui;

import com.scientificlens.harness.core.LensRegistry;
import com.scientificlens.harness.core.AnalysisLibrary;
import com.scientificlens.harness.core.Orchestrator;
import com.scientificlens.harness.core.Reporting;
import com.scientificlens.harness.core.PdfReportWriter;
import com.scientificlens.harness.core.Router;
import com.scientificlens.harness.domain.HarnessResult;
import com.scientificlens.harness.domain.AgentEvent;
import com.scientificlens.harness.domain.LensProfile;
import com.scientificlens.harness.domain.Mode;
import com.scientificlens.harness.domain.UserProblem;
import com.scientificlens.harness.domain.StoredAnalysis;
import com.scientificlens.harness.llm.CliEngine;
import com.scientificlens.harness.llm.CliLlmRuntime;
import com.scientificlens.harness.llm.CliProvider;
import com.scientificlens.harness.llm.CliSettings;
import com.scientificlens.harness.llm.LlamaEngine;
import com.scientificlens.harness.llm.LlmSettings;
import com.scientificlens.harness.llm.LocalLlmRuntime;
import com.scientificlens.harness.llm.ModelDownloader;
import com.scientificlens.harness.llm.ModelPreset;
import com.scientificlens.harness.runtime.HarnessRuntime;
import com.scientificlens.harness.runtime.MockRuntime;
import com.scientificlens.harness.runtime.StageContext;
import java.awt.Desktop;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.Dialog;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.layout.StackPane;
import javafx.stage.FileChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/** 의회 오프닝과 질문 작성 화면을 열고, 분석 실행·취소·보고서 저장을 연결하는 JavaFX 앱입니다. */
public final class MainApp extends Application {
    private static final List<String> EXAMPLES = List.of(
            "AI로 농산물 폐기량을 줄이는 서비스를 만들 수 있을까?",
            "B2B SaaS 스타트업 아이디어를 검증하고 싶다",
            "저전력 센서 네트워크로 창고 온도를 안정적으로 관리할 수 있을까?");
    private static final List<String> FINAL_STAGES = List.of("렌즈 간 토론", "레드팀 비평", "실험 설계", "최종 결정 종합");

    // Single worker: one model, one generation at a time.
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "harness-worker");
        t.setDaemon(true);
        return t;
    });
    private final Orchestrator orchestrator = new Orchestrator();
    private final ExecutorService libraryWorker = Executors.newSingleThreadExecutor(r -> new Thread(r, "research-library"));
    private final AnalysisLibrary library = new AnalysisLibrary(Path.of(System.getProperty("slh.library.path",
            LlmSettings.HOME.resolve("library").toString())));
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicBoolean cancelRequested = new AtomicBoolean();
    private final BooleanProperty busy = new SimpleBooleanProperty(false);
    private final BooleanProperty hasResult = new SimpleBooleanProperty(false);
    private final BooleanProperty pdfExporting = new SimpleBooleanProperty(false);
    private final BooleanProperty librarySaving = new SimpleBooleanProperty(false);
    private final BooleanProperty librarySaveFailed = new SimpleBooleanProperty(false);
    private final BooleanProperty libraryBlocked = new SimpleBooleanProperty(false);
    private final ToggleButton readingFocus = new ToggleButton("집중 읽기");
    private final BooleanProperty homeShown = new SimpleBooleanProperty(true);
    private final BooleanProperty reducedMotion = new SimpleBooleanProperty();
    private final ChangeListener<Boolean> systemMotion = (obs, previous, reduced) -> reducedMotion.set(reduced);
    private WelcomeView welcome;
    private CouncilOpeningView opening;

    private LlmSettings settings = LlmSettings.load();
    private CliSettings cliSettings = CliSettings.load();
    private LocalLlmRuntime llmRuntime; // cached across runs; accessed only on the worker thread
    private HarnessResult lastResult;
    private StoredAnalysis lastStored;
    private Path modelPath = settings.modelPath();

    private final TextArea question = new TextArea();
    private final TextField domainHint = new TextField();
    private final ToggleGroup modeGroup = new ToggleGroup();
    private final ToggleGroup engineGroup = new ToggleGroup();
    private final ToggleButton engineCli = segment("CLI (Kiro/GPT)", engineGroup);
    private final ToggleButton engineLlm = segment("로컬 LLM", engineGroup);
    private final ToggleButton engineMock = segment("Mock", engineGroup);
    private final Label modeCaption = ResultView.text("", "caption");
    private final Label modelName = ResultView.text("", "field-value");
    private final Label modelStatus = ResultView.text("", "status-ok");
    private final ComboBox<ModelPreset> preset = new ComboBox<>();
    private final ComboBox<CliProvider> cliProvider = new ComboBox<>();
    private final ComboBox<String> cliModel = new ComboBox<>();
    private final Label cliStatus = ResultView.text("", "status-ok");
    private final Label engineChip = new Label();

    // window-drag offset for the custom (undecorated) title bar
    private double dragOffsetX;
    private double dragOffsetY;

    private final StageTracker stages = new StageTracker();
    private final ProgressBar progress = new ProgressBar(0);
    private final Label status = ResultView.text("준비됨", "status-text");

    private final ResultView results = new ResultView();
    private final TextArea log = new TextArea();
    private final ActivityView activity = new ActivityView(log);
    private final TabPane tabs = new TabPane();
    private final MenuItem savePdf = new MenuItem("PDF 보고서 저장…");
    private final MenuItem saveMd = new MenuItem("Markdown 저장");
    private final MenuItem saveJson = new MenuItem("JSON 저장");
    private final Button runButton = new Button("분석 실행");
    private final MenuButton exportMenu = new MenuButton("내보내기");
    private final WorkflowBar workflow = new WorkflowBar(this::navigate);
    private Dialog<Void> settingsDialog;
    private AnalysisLibraryView libraryView;
    private Stage mainStage;
    private Boolean cliAvailable;
    private Thread cliProbeThread;
    private boolean waitingForCli;

    @Override
    public void start(Stage stage) {
        mainStage = stage;
        String motionOverride = System.getProperty("slh.reducedMotion");
        if (motionOverride == null) {
            var preferences = Platform.getPreferences();
            reducedMotion.set(preferences.isReducedMotion());
            preferences.reducedMotionProperty().addListener(systemMotion);
        } else {
            reducedMotion.set(Boolean.parseBoolean(motionOverride));
        }
        for (String face : List.of("Regular", "Bold")) {
            try (var font = MainApp.class.getResourceAsStream("/fonts/NotoSansKR-" + face + ".ttf")) {
                if (font != null) javafx.scene.text.Font.loadFont(font, 16);
            } catch (IOException e) {
                // The native system font remains available; PDF verifies its own bundled resources.
            }
        }
        BorderPane root = new BorderPane();
        root.getStyleClass().add("app-root");
        if (javafx.scene.text.Font.getFamilies().contains("Noto Sans KR")) {
            root.setStyle("-fx-font-family: 'Noto Sans KR';");
        }
        var screen = Screen.getPrimary().getVisualBounds();
        StackPane layers = new StackPane(root);
        Scene scene = new Scene(layers, Math.min(1568, screen.getWidth()),
                Math.min(1004, screen.getHeight()));
        scene.getStylesheets().add(MainApp.class.getResource("app.css").toExternalForm());
        stage.initStyle(StageStyle.UNDECORATED);
        stage.setTitle("Scientific Lens Harness · Workflow Bench");
        stage.setMinWidth(1000);
        stage.setMinHeight(740);
        stage.setScene(scene);
        settingsDialog = buildSettings(stage);
        libraryBlocked.bind(busy.or(pdfExporting));
        libraryView = new AnalysisLibraryView(stage, library, libraryWorker, libraryBlocked, this::restoreReport);
        root.setTop(buildHeader(stage));
        Node workbench = buildWorkbench(stage);
        workbench.visibleProperty().bind(homeShown.not());
        workbench.managedProperty().bind(workbench.visibleProperty());
        welcome = new WelcomeView(question.textProperty(), modeGroup, busy, hasResult,
                runButton, () -> homeShown.set(false), libraryView::show);
        welcome.visibleProperty().bind(homeShown);
        welcome.managedProperty().bind(welcome.visibleProperty());
        root.setCenter(new StackPane(workbench, welcome));
        Node statusBar = buildStatusBar(stage);
        statusBar.visibleProperty().bind(homeShown.not());
        statusBar.managedProperty().bind(statusBar.visibleProperty());
        root.setBottom(statusBar);

        root.setMouseTransparent(true);
        StackPane openingLayer = new StackPane();
        opening = new CouncilOpeningView(reducedMotion, windowControls(stage),
                () -> root.setVisible(true), () -> {
                    layers.getChildren().remove(openingLayer);
                    opening = null;
                    root.setCache(false);
                    root.setMouseTransparent(false);
                    installWorkspaceShortcuts(scene);
                    welcome.focusInput();
                });
        openingLayer.getChildren().add(opening);
        layers.getChildren().add(openingLayer);
        enableWindowDrag(opening.dragStrip(), stage);
        stage.setOnCloseRequest(e -> shutdown());
        stage.show();
        // Warm the first workspace paint under the cover, before the timed entrance starts.
        if (!reducedMotion.get()) {
            root.setCache(true);
            root.snapshot(null, null);
        }
        root.setVisible(false);
        opening.present();
    }

    private void installWorkspaceShortcuts(Scene scene) {
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.ENTER, KeyCombination.SHORTCUT_DOWN), runButton::fire);
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.L, KeyCombination.SHORTCUT_DOWN), libraryView::show);
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.P, KeyCombination.SHORTCUT_DOWN), () -> {
            if (!savePdf.isDisable()) savePdf.fire();
        });
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.R,
                KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN), () -> {
            if (!readingFocus.isDisabled()) readingFocus.fire();
        });
    }

    // ------------------------------------------------------------------ layout

    private Node buildHeader(Stage stage) {
        Label logo = styled(new Label("SL"), "logo");
        VBox titles = new VBox(1,
                styled(new Label("Scientific Lens Harness"), "header-title"),
                styled(new Label("아이디어를 검증 가능한 가설과 첫 실험으로"), "header-sub"));
        Label bench = styled(new Label("Research Studio"), "workbench-name");
        bench.visibleProperty().bind(stage.widthProperty().greaterThanOrEqualTo(1250)
                .and(readingFocus.selectedProperty().not().or(stage.widthProperty().greaterThanOrEqualTo(1450))));
        bench.managedProperty().bind(bench.visibleProperty());
        Button settingsButton = secondary("설정");
        Button home = secondary("홈");
        home.setId("home-button");
        home.setGraphic(WorkbenchUi.icon("mdi2h-home-outline", "icon-small"));
        home.disableProperty().bind(busy.or(pdfExporting));
        home.setOnAction(e -> { homeShown.set(true); welcome.focusInput(); });
        ToggleButton motion = new ToggleButton("모션");
        motion.setId("reduce-motion");
        motion.getStyleClass().add("reader-toggle");
        motion.setGraphic(WorkbenchUi.icon("mdi2p-pause", "icon-small"));
        motion.setAccessibleText("움직임 줄이기");
        motion.setTooltip(new Tooltip("선택하면 움직임을 줄입니다"));
        motion.selectedProperty().bindBidirectional(reducedMotion);
        reducedMotion.addListener((o, before, reduced) -> activity.setReducedMotion(reduced));
        activity.setReducedMotion(reducedMotion.get());
        Button libraryButton = secondary("보관함");
        libraryButton.setId("open-library");
        libraryButton.setGraphic(WorkbenchUi.icon("mdi2b-bookshelf", "icon-small"));
        libraryButton.setTooltip(new Tooltip("저장된 연구를 검색하고 다시 열기 · Ctrl+L"));
        libraryButton.setOnAction(e -> libraryView.show());
        settingsButton.setId("settings-button");
        settingsButton.setGraphic(WorkbenchUi.icon("mdi2c-cog-outline"));
        settingsButton.getStyleClass().add("settings-button");
        settingsButton.setOnAction(e -> settingsDialog.show());
        engineChip.getStyleClass().add("chip");
        engineChip.setAccessibleText("현재 분석 엔진");
        engineChip.visibleProperty().bind(stage.widthProperty().greaterThanOrEqualTo(1450));
        engineChip.managedProperty().bind(engineChip.visibleProperty());
        Label date = styled(new Label(java.time.LocalDate.now().toString()), "header-date");
        date.visibleProperty().bind(stage.widthProperty().greaterThanOrEqualTo(1400));
        date.managedProperty().bind(date.visibleProperty());
        MenuButton readerExport = readerExportMenu();
        readerExport.visibleProperty().bind(readingFocus.selectedProperty().and(homeShown.not()));
        readerExport.managedProperty().bind(readerExport.visibleProperty());
        HBox bar = new HBox(12, logo, titles, bench, WorkbenchUi.spacer(), readerExport,
                home, libraryButton, settingsButton, motion, engineChip, date, windowControls(stage));
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("header-bar");
        enableWindowDrag(bar, stage);
        return bar;
    }

    /** Minimize / maximize (restore) / close buttons for the undecorated window. */
    private HBox windowControls(Stage stage) {
        Button minimize = windowButton("\u2013", "win-min"); // en-dash
        minimize.setAccessibleText("창 최소화");
        minimize.setOnAction(e -> stage.setIconified(true));
        Button maximize = windowButton("\u25A1", "win-max"); // square
        maximize.textProperty().bind(Bindings.when(stage.maximizedProperty()).then("\u2750").otherwise("\u25A1"));
        maximize.accessibleTextProperty().bind(Bindings.when(stage.maximizedProperty()).then("원래 크기로").otherwise("창 최대화"));
        maximize.setOnAction(e -> toggleMaximize(stage));
        Button close = windowButton("\u2715", "win-close"); // multiplication x
        close.setAccessibleText("창 닫기");
        close.setOnAction(e -> shutdown());
        HBox box = new HBox(4, minimize, maximize, close);
        box.setAlignment(Pos.CENTER_RIGHT);
        return box;
    }

    private void toggleMaximize(Stage stage) {
        if (stage.isMaximized()) {
            stage.setMaximized(false);
        } else {
            // clamp to the visual bounds of the screen the window is currently on
            Screen screen = Screen.getScreensForRectangle(stage.getX(), stage.getY(), 1, 1)
                    .stream().findFirst().orElse(Screen.getPrimary());
            var b = screen.getVisualBounds();
            stage.setX(b.getMinX());
            stage.setY(b.getMinY());
            stage.setMaximized(true);
        }
    }

    /** Drag the window by its header (undecorated stages have no OS title bar). */
    private void enableWindowDrag(Node bar, Stage stage) {
        bar.setOnMousePressed(e -> {
            if (isHeaderControl(e.getTarget())) { return; }
            dragOffsetX = e.getScreenX() - stage.getX();
            dragOffsetY = e.getScreenY() - stage.getY();
        });
        bar.setOnMouseDragged(e -> {
            if (stage.isMaximized() || isHeaderControl(e.getTarget())) {
                return;
            }
            stage.setX(e.getScreenX() - dragOffsetX);
            stage.setY(e.getScreenY() - dragOffsetY);
        });
    }

    private static boolean isHeaderControl(Object target) {
        if (!(target instanceof Node node)) { return false; }
        for (Node current = node; current != null; current = current.getParent()) {
            if (current instanceof ButtonBase) { return true; }
        }
        return false;
    }

    private static Button windowButton(String glyph, String styleClass) {
        Button b = new Button(glyph);
        b.getStyleClass().addAll("window-button", styleClass);
        b.setFocusTraversable(false);
        return b;
    }

    private Dialog<Void> buildSettings(Stage stage) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(stage);
        dialog.setTitle("설정 · 분석 엔진");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().getStylesheets().add(MainApp.class.getResource("app.css").toExternalForm());
        engineCli.setId("engine-cli");
        engineLlm.setId("engine-local");
        engineMock.setId("engine-mock");
        HBox engines = new HBox(engineCli, engineLlm, engineMock);
        for (Node engine : engines.getChildren()) {
            HBox.setHgrow(engine, Priority.ALWAYS);
            engine.disableProperty().bind(busy);
        }
        roundSegments(engines);
        cliProvider.getItems().setAll(CliProvider.values());
        cliProvider.setValue(cliSettings.provider());
        cliProvider.setMaxWidth(Double.MAX_VALUE);
        cliProvider.setAccessibleText("CLI 제공자");
        cliModel.setEditable(true);
        cliModel.setMaxWidth(Double.MAX_VALUE);
        cliModel.setAccessibleText("CLI 모델");
        cliProvider.valueProperty().addListener((obs, old, now) -> onCliProviderChanged(now));
        cliModel.valueProperty().addListener((obs, old, now) -> {
            if (now != null && !now.isBlank()) {
                cliSettings = cliSettings.with(cliProvider.getValue(), now);
            }
        });
        onCliProviderChanged(cliSettings.provider());
        cliModel.setValue(cliSettings.model());
        cliProvider.disableProperty().bind(busy);
        cliModel.disableProperty().bind(busy);
        HBox.setHgrow(cliProvider, Priority.ALWAYS);
        HBox.setHgrow(cliModel, Priority.ALWAYS);
        HBox cliRow = new HBox(8, cliProvider, cliModel);
        VBox cliBox = new VBox(8, ResultView.text("CLI 백엔드", "field-title"), cliRow, cliStatus,
                ResultView.text("설치된 CLI를 사용합니다. 실행 전에 해당 CLI에서 로그인을 완료하세요. API 키는 필요 없습니다.", "caption"));
        cliBox.visibleProperty().bind(engineCli.selectedProperty());
        cliBox.managedProperty().bind(cliBox.visibleProperty());
        engineGroup.selectToggle(engineCli);
        engineGroup.selectedToggleProperty().addListener((obs, old, now) -> refreshEngine());

        Button browse = link("다른 모델 파일 선택…");
        browse.setOnAction(e -> chooseModel(stage));
        preset.getItems().setAll(ModelPreset.values());
        preset.setValue(ModelPreset.QWEN25_3B);
        preset.setMaxWidth(Double.MAX_VALUE);
        preset.setAccessibleText("다운로드할 권장 모델");
        Button download = secondary("다운로드");
        download.setOnAction(e -> downloadModel());
        HBox.setHgrow(preset, Priority.ALWAYS);
        HBox downloadRow = new HBox(8, preset, download);
        downloadRow.setAlignment(Pos.CENTER_LEFT);
        browse.disableProperty().bind(busy);
        preset.disableProperty().bind(busy);
        download.disableProperty().bind(busy);
        VBox modelBox = new VBox(8, ResultView.text("GGUF 모델", "field-title"), modelName, modelStatus, browse,
                ResultView.text("권장 모델 받기", "field-title"), downloadRow);
        modelBox.visibleProperty().bind(engineLlm.selectedProperty());
        modelBox.managedProperty().bind(modelBox.visibleProperty());
        Label mockNote = ResultView.text("Mock은 고정 템플릿을 사용합니다. 화면과 분석 흐름을 확인하는 테스트 엔진입니다.", "caption");
        mockNote.visibleProperty().bind(engineMock.selectedProperty());
        mockNote.managedProperty().bind(mockNote.visibleProperty());
        VBox engineCard = card("분석 엔진", engines, cliBox, modelBox, mockNote);

        domainHint.setId("domain-hint");
        domainHint.setPromptText("선택 · 예: 농업, 교육, SaaS");
        domainHint.setAccessibleText("도메인 힌트");
        domainHint.disableProperty().bind(busy);
        FlowPane examples = new FlowPane(8, 8);
        for (String example : EXAMPLES) {
            Button chip = new Button(example.length() > 30 ? example.substring(0, 29) + "…" : example);
            chip.getStyleClass().add("example-chip");
            chip.setTooltip(new Tooltip(example));
            chip.setOnAction(e -> { question.setText(example); dialog.close(); question.requestFocus(); });
            chip.disableProperty().bind(busy);
            examples.getChildren().add(chip);
        }
        VBox inputCard = card("질문 설정", ResultView.text("도메인 힌트 (선택)", "caption"), domainHint,
                modeCaption, ResultView.text("질문 예시", "caption"), examples);
        VBox stageCard = card("단계별 진행 상황", stages);
        VBox content = new VBox(16, engineCard, inputCard, stageCard);
        content.getStyleClass().add("settings-content");
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefViewportWidth(600);
        scroll.setPrefViewportHeight(530);
        scroll.getStyleClass().add("page-scroll");
        dialog.getDialogPane().setContent(scroll);
        dialog.setResizable(true);
        return dialog;
    }

    private MenuButton readerExportMenu() {
        MenuButton menu = new MenuButton("PDF 내보내기");
        menu.setId("report-export-menu");
        menu.getStyleClass().add("reader-export");
        menu.setGraphic(WorkbenchUi.icon("mdi2t-tray-arrow-down", "icon-small"));
        menu.setAccessibleText("완성된 결과를 PDF, Markdown 또는 JSON으로 내보내기");
        menu.disableProperty().bind(busy.or(hasResult.not()).or(pdfExporting));
        menu.textProperty().bind(Bindings.when(pdfExporting).then("PDF 생성 중…").otherwise("PDF 내보내기"));
        for (MenuItem action : List.of(savePdf, saveMd, saveJson)) {
            MenuItem item = new MenuItem(action.getText());
            item.textProperty().bind(action.textProperty());
            item.disableProperty().bind(action.disableProperty());
            item.setOnAction(event -> action.fire());
            menu.getItems().add(item);
        }
        return menu;
    }

    private Node buildWorkbench(Stage stage) {
        Node toolbar = buildToolbar(stage);
        log.setId("live-log");
        log.setEditable(false);
        log.setWrapText(true);
        log.getStyleClass().add("log-area");
        log.setPromptText("분석 단계와 생성 중인 출력을 실시간으로 확인합니다.");
        tabs.setId("result-tabs");
        tabs.getTabs().setAll(
                new Tab("요약", results.summaryPage()),
                new Tab("렌즈 분석", results.lensPage()),
                new Tab("토론 · 레드팀", results.critiquePage()),
                new Tab("실험 계획", results.experimentPage()),
                new Tab("실시간 로그", activity),
                new Tab("Markdown", results.markdownArea()),
                new Tab("JSON", results.jsonArea()));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getStyleClass().addAll("results-tabs", "summary-mode");
        tabs.getSelectionModel().selectedIndexProperty().addListener((obs, old, selected) -> {
            tabs.getStyleClass().remove("summary-mode");
            if (selected.intValue() == 0) {
                tabs.getStyleClass().add("summary-mode");
            }
            if (selected.intValue() <= 3) {
                workflow.showPage(selected.intValue());
            }
        });
        VBox.setVgrow(tabs, Priority.ALWAYS);
        VBox editing = new VBox(toolbar, workflow);
        editing.setId("analysis-settings-region");
        editing.visibleProperty().bind(readingFocus.selectedProperty().not().and(busy.not()));
        editing.managedProperty().bind(editing.visibleProperty());
        VBox center = new VBox(editing, tabs);
        center.setMinWidth(0);
        return center;
    }

    private Node buildToolbar(Stage stage) {
        question.setId("question-input");
        question.setPromptText("검증하고 싶은 아이디어나 질문을 입력하세요");
        question.setPrefRowCount(1);
        question.setWrapText(false);
        question.setMinWidth(0);
        question.setMinHeight(44);
        question.setPrefHeight(44);
        question.setMaxHeight(44);
        question.getStyleClass().add("question-input");
        question.setAccessibleText("질문 또는 아이디어 입력. Ctrl+Enter로 분석 실행");
        question.disableProperty().bind(busy);
        HBox.setHgrow(question, Priority.ALWAYS);
        Button clear = iconButton("mdi2c-close-circle-outline", "질문 지우기");
        clear.setId("clear-question");
        clear.disableProperty().bind(busy.or(question.textProperty().isEmpty()));
        clear.setOnAction(e -> { question.clear(); question.requestFocus(); });
        Button expand = iconButton("mdi2a-arrow-expand", "질문 크게 편집");
        expand.setId("expand-question");
        expand.disableProperty().bind(busy);
        expand.setOnAction(e -> editQuestion(stage));
        HBox input = new HBox(8, WorkbenchUi.icon("mdi2m-magnify", "icon-muted"), question, clear, expand);
        input.setAlignment(Pos.CENTER_LEFT);
        input.setMinWidth(0);
        input.getStyleClass().add("question-shell");
        question.focusedProperty().addListener((obs, old, focused) ->
                input.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("focused"), focused));
        VBox idea = toolbarGroup("질문 / 아이디어", input);
        idea.setMinWidth(0);

        HBox modes = new HBox();
        for (Mode mode : Mode.values()) {
            ToggleButton button = segment(switch (mode) {
                case FAST -> "빠름";
                case RESEARCH -> "연구";
                case COUNCIL -> "전체";
            }, modeGroup);
            button.setId("mode-" + mode.value());
            button.setUserData(mode);
            button.disableProperty().bind(busy);
            button.setAccessibleText("분석 모드 " + button.getText());
            HBox.setHgrow(button, Priority.ALWAYS);
            modes.getChildren().add(button);
        }
        roundSegments(modes);
        modeGroup.selectedToggleProperty().addListener((obs, old, now) -> updateModeCaption());
        modeGroup.selectToggle(modeGroup.getToggles().get(1));
        VBox mode = toolbarGroup("분석 모드", modes);
        mode.setPrefWidth(300);

        runButton.setId("run-analysis");
        runButton.getStyleClass().add("primary-button");
        runButton.setGraphic(WorkbenchUi.icon("mdi2p-play"));
        runButton.setMaxWidth(Double.MAX_VALUE);
        runButton.setTooltip(new Tooltip("Ctrl+Enter"));
        runButton.setOnAction(e -> runAnalysis());
        runButton.disableProperty().bind(busy.or(pdfExporting).or(Bindings.createBooleanBinding(
                () -> question.getText().isBlank(), question.textProperty())));
        VBox run = toolbarGroup(" ", runButton);
        run.setPrefWidth(180);
        savePdf.setId("save-pdf");
        savePdf.setOnAction(e -> choosePdfDestination(stage));
        savePdf.disableProperty().bind(busy.or(hasResult.not()).or(pdfExporting));
        saveMd.setOnAction(e -> save(stage, "report.md", Reporting.markdown(lastResult)));
        saveJson.setOnAction(e -> save(stage, "report.json", Reporting.json(lastResult)));
        saveMd.setDisable(true);
        saveJson.setDisable(true);
        exportMenu.setId("export-menu");
        exportMenu.setGraphic(WorkbenchUi.icon("mdi2t-tray-arrow-down"));
        exportMenu.getStyleClass().add("export-menu");
        exportMenu.setMaxWidth(Double.MAX_VALUE);
        exportMenu.disableProperty().bind(busy.or(saveMd.disableProperty()).or(pdfExporting));
        exportMenu.getItems().setAll(savePdf, saveMd, saveJson);
        VBox export = toolbarGroup(" ", exportMenu);
        export.setPrefWidth(180);

        GridPane toolbar = new GridPane();
        toolbar.getStyleClass().add("workbench-toolbar");
        toolbar.setMinWidth(0);
        toolbar.widthProperty().addListener((obs, old, width) ->
                layoutToolbar(toolbar, idea, mode, run, export, width.doubleValue() >= 1200));
        layoutToolbar(toolbar, idea, mode, run, export, true);
        refreshEngine();
        return toolbar;
    }

    private static void layoutToolbar(GridPane grid, Node idea, Node mode, Node run, Node export, boolean wide) {
        grid.getChildren().clear();
        grid.getColumnConstraints().clear();
        if (wide) {
            ColumnConstraints flexible = new ColumnConstraints();
            flexible.setMinWidth(0);
            flexible.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().setAll(flexible, new ColumnConstraints(300),
                    new ColumnConstraints(180), new ColumnConstraints(180));
            grid.add(idea, 0, 0);
            grid.add(mode, 1, 0);
            grid.add(run, 2, 0);
            grid.add(export, 3, 0);
        } else {
            ColumnConstraints flexible = new ColumnConstraints();
            flexible.setMinWidth(300);
            flexible.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().setAll(flexible, new ColumnConstraints(180), new ColumnConstraints(180));
            grid.add(idea, 0, 0, 3, 1);
            grid.add(mode, 0, 1);
            grid.add(run, 1, 1);
            grid.add(export, 2, 1);
        }
    }

    private static VBox toolbarGroup(String title, Node control) {
        VBox group = new VBox(8, ResultView.text(title, "toolbar-label"), control);
        group.getStyleClass().add("toolbar-group");
        return group;
    }

    private void navigate(int page) {
        homeShown.set(false);
        tabs.getSelectionModel().select(page);
        if (page == 0 && !readingFocus.isSelected()) {
            question.requestFocus();
        }
    }

    private void editQuestion(Stage stage) {
        Dialog<String> editor = new Dialog<>();
        editor.initOwner(stage);
        editor.setTitle("질문 / 아이디어 편집");
        TextArea text = new TextArea(question.getText());
        text.setId("expanded-question");
        text.setWrapText(true);
        text.setPrefSize(700, 280);
        editor.getDialogPane().setContent(text);
        editor.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        editor.getDialogPane().getStylesheets().add(MainApp.class.getResource("app.css").toExternalForm());
        editor.setResultConverter(button -> button == ButtonType.OK ? text.getText() : null);
        editor.showAndWait().ifPresent(question::setText);
        question.requestFocus();
    }

    private Node buildStatusBar(Stage stage) {
        progress.setId("analysis-progress");
        progress.setPrefWidth(140);
        progress.setMaxWidth(140);
        progress.setPrefHeight(6);
        progress.setAccessibleText("분석 진행률");
        status.setId("analysis-status");
        status.setWrapText(false);
        status.setMinWidth(0);
        HBox.setHgrow(status, Priority.ALWAYS);
        Button cancel = secondary("취소");
        cancel.setId("cancel-analysis");
        cancel.disableProperty().bind(busy.not());
        cancel.setOnAction(e -> {
            cancelRequested.set(true);
            if (waitingForCli) {
                waitingForCli = false;
                stopCliProbe();
                cliStatus.setText("설치 확인이 취소되었습니다. 다음 실행 때 다시 확인합니다.");
                refreshEngine();
                finish("취소됨");
                return;
            }
            status.textProperty().unbind();
            status.setText("취소 요청됨 · 현재 단계가 끝나면 멈춥니다");
        });
        MenuButton view = new MenuButton("상세 보기");
        view.setId("view-menu");
        view.getStyleClass().add("view-menu");
        for (int i = 0; i < tabs.getTabs().size(); i++) {
            final int page = i;
            MenuItem item = new MenuItem(tabs.getTabs().get(i).getText());
            item.setOnAction(e -> navigate(page));
            view.getItems().add(item);
        }
        Button logs = link("실시간 로그");
        logs.setId("show-log");
        logs.setOnAction(e -> navigate(4));
        Label resize = new Label();
        resize.setGraphic(WorkbenchUi.icon("mdi2r-resize-bottom-right", "icon-muted"));
        resize.getStyleClass().add("resize-handle");
        resize.setCursor(javafx.scene.Cursor.SE_RESIZE);
        double[] origin = new double[4];
        resize.setOnMousePressed(e -> {
            origin[0] = e.getScreenX(); origin[1] = e.getScreenY();
            origin[2] = stage.getWidth(); origin[3] = stage.getHeight();
            e.consume();
        });
        resize.setOnMouseDragged(e -> {
            if (!stage.isMaximized()) {
                stage.setWidth(Math.max(stage.getMinWidth(), origin[2] + e.getScreenX() - origin[0]));
                stage.setHeight(Math.max(stage.getMinHeight(), origin[3] + e.getScreenY() - origin[1]));
            }
            e.consume();
        });
        readingFocus.setId("reading-focus");
        readingFocus.selectedProperty().addListener((observable, previous, focusedReading) ->
                Platform.runLater(() -> {
                    if (focusedReading) {
                        Node report = tabs.getSelectionModel().getSelectedItem().getContent();
                        report.setFocusTraversable(true);
                        report.requestFocus();
                    } else if (!busy.get()) {
                        question.requestFocus();
                    }
                }));
        readingFocus.getStyleClass().add("reader-toggle");
        readingFocus.setGraphic(WorkbenchUi.icon("mdi2b-book-open-page-variant", "icon-small"));
        readingFocus.disableProperty().bind(busy.or(hasResult.not()));
        readingFocus.textProperty().bind(Bindings.when(readingFocus.selectedProperty())
                .then("분석 설정").otherwise("집중 읽기"));
        readingFocus.accessibleTextProperty().bind(Bindings.when(readingFocus.selectedProperty())
                .then("집중 읽기 켜짐. 분석 설정을 표시합니다.").otherwise("결과에 집중하도록 분석 설정을 접습니다."));
        readingFocus.setTooltip(new Tooltip("결과 집중 읽기 / 분석 설정 · Ctrl+Shift+R"));
        Button retrySave = link("저장 실패 · 다시 저장");
        retrySave.setId("retry-library-save");
        retrySave.visibleProperty().bind(librarySaveFailed);
        retrySave.managedProperty().bind(retrySave.visibleProperty());
        retrySave.disableProperty().bind(busy.or(librarySaving).or(hasResult.not()));
        retrySave.setOnAction(e -> saveCompleted(lastResult));
        HBox bar = new HBox(12, progress, status, retrySave, cancel, readingFocus, logs, view, resize);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("status-bar");
        return bar;
    }

    // ------------------------------------------------------------------ actions

    private void saveCompleted(HarnessResult snapshot) {
        if (snapshot == null || closing.get() || (snapshot == lastResult && librarySaving.get())) return;
        librarySaving.set(true);
        librarySaveFailed.set(false);
        if (!busy.get() && !pdfExporting.get()) status.setText("완료 · 보관함에 저장 중…");
        Task<StoredAnalysis> task = new Task<>() {
            @Override protected StoredAnalysis call() throws IOException { return library.save(snapshot); }
        };
        task.setOnSucceeded(e -> {
            if (lastResult != snapshot || closing.get()) return;
            lastStored = task.getValue();
            librarySaving.set(false);
            if (!busy.get() && !pdfExporting.get()) status.setText("완료 · 렌즈 " + snapshot.selectedLenses().size() + "개 · 보관함에 저장됨");
        });
        task.setOnFailed(e -> {
            if (lastResult != snapshot || closing.get()) return;
            librarySaving.set(false);
            librarySaveFailed.set(true);
            if (!busy.get() && !pdfExporting.get()) status.setText("완료 · 자동 저장 실패. 결과를 내보내거나 다시 저장하세요.");
            log.appendText("\n[보관함 저장 실패] " + task.getException().getMessage() + "\n");
        });
        libraryWorker.submit(task);
    }

    void restoreReport(StoredAnalysis record) {
        if (busy.get() || pdfExporting.get() || closing.get()) return;
        lastResult = record.result();
        homeShown.set(false);
        lastStored = record;
        librarySaving.set(false);
        librarySaveFailed.set(false);
        question.setText(lastResult.problem().question());
        domainHint.setText(lastResult.problem().domainHint() == null ? "" : lastResult.problem().domainHint());
        modeGroup.getToggles().stream().filter(toggle -> toggle.getUserData() == lastResult.problem().mode())
                .findFirst().ifPresent(modeGroup::selectToggle);
        results.show(lastResult);
        hasResult.set(true);
        saveMd.setDisable(false);
        saveJson.setDisable(false);
        List<String> names = new ArrayList<>();
        lastResult.selectedLenses().stream().map(Reporting::agentLabel).forEach(names::add);
        names.addAll(FINAL_STAGES);
        stages.reset(names);
        stages.update(names.size());
        workflow.begin(lastResult.selectedLenses().size());
        workflow.finish();
        progress.progressProperty().unbind();
        progress.setProgress(1);
        finish("저장된 보고서 · " + AnalysisLibraryView.timeLabel(record.savedAt()));
        activity.replay(lastResult, record.savedAt());
        tabs.getSelectionModel().select(0);
        readingFocus.setSelected(true);
    }

    private void runAnalysis() {
        if (closing.get() || runButton.isDisabled()) {
            return;
        }
        UserProblem problem;
        try {
            problem = new UserProblem(question.getText(), selectedMode(), domainHint.getText());
        } catch (IllegalArgumentException e) {
            error("입력 오류", e.getMessage());
            return;
        }
        boolean useCli = engineCli.isSelected();
        boolean useLlm = engineLlm.isSelected();
        if (useCli && !Boolean.TRUE.equals(cliAvailable)) {
            waitingForCli = true;
            cancelRequested.set(false);
            busy.set(true);
            status.setText("CLI 설치 확인 중…");
            runButton.setText("CLI 확인 중…");
            if (cliProbeThread == null) probeCli(cliProvider.getValue());
            return;
        }
        if (useLlm && !Files.isRegularFile(modelPath)) {
            error("모델 없음", "GGUF 모델 파일을 찾을 수 없습니다.\n'권장 모델 받기'로 다운로드하거나 모델 파일을 선택하세요.\n\n" + modelPath);
            return;
        }
        if (useLlm && !modelPath.equals(settings.modelPath())) {
            settings = settings.withModelPath(modelPath);
            settings.save();
        }
        CliProvider cliBackend = cliProvider.getValue();
        String cliModelId = cliModel.getValue();
        if (useCli) {
            if (cliModelId == null || cliModelId.isBlank()) {
                cliModelId = cliBackend.defaultModel();
            }
            cliSettings = cliSettings.with(cliBackend, cliModelId);
            cliSettings.save();
        }

        List<String> stageNames = new ArrayList<>();
        if (useCli || useLlm) {
            stageNames.add("오케스트레이터 · 에이전트 구성");
        } else {
            Router.select(problem, LensRegistry.load()).stream().map(LensProfile::displayName).forEach(stageNames::add);
        }
        stageNames.addAll(FINAL_STAGES);
        stages.reset(stageNames);
        workflow.begin(useCli || useLlm ? 0 : stageNames.size() - FINAL_STAGES.size());

        readingFocus.setSelected(false);
        homeShown.set(false);
        hasResult.set(false);
        cancelRequested.set(false);
        log.clear();
        activity.begin(!useCli && !useLlm);
        results.loading();
        lastResult = null;
        lastStored = null;
        librarySaving.set(false);
        librarySaveFailed.set(false);
        saveMd.setDisable(true);
        saveJson.setDisable(true);
        runButton.setText("분석 중…");
        tabs.getSelectionModel().select(4);

        StageContext ctx = new StageContext() {
            @Override
            public boolean isCancelled() {
                return cancelRequested.get();
            }

            @Override
            public void onToken(String token) {
                Platform.runLater(() -> log.appendText(token));
            }

            @Override
            public void onLensesSelected(List<String> lensIds) {
                Platform.runLater(() -> {
                    stageNames.clear();
                    var catalog = LensRegistry.load();
                    for (String id : lensIds) {
                        stageNames.add(catalog.stream().filter(lens -> lens.lensId().equals(id))
                                .findFirst().map(LensProfile::displayName).orElse(id));
                    }
                    stageNames.addAll(FINAL_STAGES);
                    stages.reset(stageNames);
                    workflow.begin(lensIds.size());
                    activity.selectLenses(lensIds);
                });
            }

            @Override
            public void onAgentEvent(AgentEvent event) {
                Platform.runLater(() -> {
                    activity.add(event);
                    log.appendText("\n── #" + event.sequence() + " "
                        + Reporting.agentLabel(event.actor()) + " → " + Reporting.agentLabel(event.recipient())
                        + " · " + Reporting.actionLabel(event.action()) + " ──\n" + event.content() + "\n");
                });
            }
        };
        LlmSettings runSettings = settings;
        final CliProvider runCliProvider = cliBackend;
        final String runCliModel = (cliModelId == null || cliModelId.isBlank()) ? cliBackend.defaultModel() : cliModelId;

        Task<HarnessResult> task = new Task<>() {
            @Override
            protected HarnessResult call() {
                HarnessRuntime rt = useCli
                        ? new CliLlmRuntime(new CliEngine(runCliProvider, runCliModel,
                                problem.mode() == Mode.FAST ? "low" : "medium"))
                        : useLlm ? llmRuntimeFor(runSettings, this::updateMessage) : new MockRuntime();
                return orchestrator.run(problem, rt, (step, total, message) -> {
                    updateProgress(step, total);
                    updateMessage(message + "  ·  " + step + "/" + total);
                    Platform.runLater(() -> {
                        stages.update(step);
                        workflow.update(step);
                        activity.stage(message);
                        log.appendText("\n── " + message + " ──\n");
                    });
                }, ctx);
            }
        };
        task.setOnSucceeded(e -> {
            if (cancelRequested.get()) {
                stopped("취소됨");
                return;
            }
            HarnessResult r = task.getValue();
            lastResult = r;
            hasResult.set(true);
            results.show(r);
            saveMd.setDisable(false);
            saveJson.setDisable(false);
            tabs.getSelectionModel().select(0);
            stages.update(stageNames.size());
            workflow.finish();
            finish("완료 · 렌즈 " + r.selectedLenses().size() + "개 분석");
            if (!useCli && !useLlm) activity.selectLenses(r.selectedLenses());
            activity.finish("분석 완료 · 가설 미검증");
            readingFocus.setSelected(true);
            saveCompleted(r);
        });
        task.setOnCancelled(e -> stopped("취소됨"));
        task.setOnFailed(e -> {
            Throwable ex = task.getException();
            if (ex instanceof CancellationException) {
                stopped("취소됨");
                return;
            }
            stopped("실패");
            // Print full stack trace to the log tab for diagnostics
            java.io.StringWriter sw = new java.io.StringWriter();
            ex.printStackTrace(new java.io.PrintWriter(sw));
            Platform.runLater(() -> log.appendText("\n\n[ERROR]\n" + sw));
            error("분석 실패", ex.getMessage() == null ? ex.toString() : ex.getMessage());
        });
        bindTask(task);
        worker.submit(task);
    }

    /** Loads (or reuses) the embedded model. Runs on the worker thread only. */
    private LocalLlmRuntime llmRuntimeFor(LlmSettings s, Consumer<String> message) {
        if (llmRuntime != null && llmRuntime.name().endsWith(s.modelPath().getFileName().toString())) {
            return llmRuntime;
        }
        if (llmRuntime != null) {
            llmRuntime.close();
            llmRuntime = null;
        }
        message.accept("모델 로딩 중… (" + s.modelPath().getFileName() + ")");
        llmRuntime = new LocalLlmRuntime(LlamaEngine.load(s));
        return llmRuntime;
    }

    private void downloadModel() {
        ModelPreset p = preset.getValue();
        Path target = p.defaultPath(LlmSettings.MODELS_DIR);
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                p + "\n\n" + p.url() + "\n\n저장 위치: " + target + "\n\n다운로드할까요? 중단해도 다시 누르면 이어받습니다.");
        confirm.initOwner(mainStage);
        confirm.getDialogPane().getStylesheets().add(MainApp.class.getResource("app.css").toExternalForm());
        confirm.setHeaderText("모델 다운로드");
        if (confirm.showAndWait().filter(b -> b.getButtonData().isDefaultButton()).isEmpty()) {
            return;
        }
        cancelRequested.set(false);
        stages.clear();
        Task<Path> task = new Task<>() {
            @Override
            protected Path call() throws Exception {
                updateMessage("다운로드 준비 중…");
                return new ModelDownloader().download(p, target, bytes -> {
                    updateProgress(bytes, p.sizeBytes());
                    updateMessage(String.format("다운로드 중 · %.0f / %.0f MB", bytes / 1e6, p.sizeBytes() / 1e6));
                }, cancelRequested::get);
            }
        };
        task.setOnSucceeded(e -> {
            modelPath = task.getValue();
            settings = settings.withModelPath(modelPath);
            settings.save();
            engineGroup.selectToggle(engineLlm);
            refreshEngine();
            finish("모델 다운로드 완료");
        });
        task.setOnFailed(e -> {
            finish("다운로드 중단 · 다시 누르면 이어받기");
            if (!(task.getException() instanceof CancellationException)) {
                error("다운로드 실패", String.valueOf(task.getException().getMessage()));
            }
        });
        bindTask(task);
        worker.submit(task);
    }

    private void chooseModel(Stage stage) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("GGUF 모델 선택");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("GGUF 모델", "*.gguf"));
        if (Files.isDirectory(LlmSettings.MODELS_DIR)) {
            chooser.setInitialDirectory(LlmSettings.MODELS_DIR.toFile());
        }
        var file = chooser.showOpenDialog(stage);
        if (file != null) {
            modelPath = file.toPath();
            engineGroup.selectToggle(engineLlm);
            refreshEngine();
        }
    }

    private void choosePdfDestination(Stage stage) {
        if (lastResult == null || busy.get() || pdfExporting.get()) return;
        FileChooser chooser = new FileChooser();
        chooser.setTitle("PDF 보고서 저장");
        chooser.setInitialFileName("scientific-lens-report-" + java.time.LocalDate.now() + ".pdf");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF 문서 (*.pdf)", "*.pdf"));
        var file = chooser.showSaveDialog(stage);
        if (file == null) return;
        Path destination = file.toPath();
        if (!destination.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) {
            destination = destination.resolveSibling(destination.getFileName() + ".pdf");
        }
        exportPdf(destination);
    }

    void exportPdf(Path destination) {
        if (lastResult == null || busy.get() || pdfExporting.get()) return;
        HarnessResult snapshot = lastResult;
        pdfExporting.set(true);
        if (!busy.get()) status.setText("PDF 보고서 만드는 중…");
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws IOException {
                PdfReportWriter.write(snapshot, destination);
                return null;
            }
        };
        task.setOnSucceeded(event -> {
            pdfExporting.set(false);
            if (!busy.get()) status.setText("PDF 저장됨 · " + destination.getFileName());
            Alert notice = new Alert(Alert.AlertType.INFORMATION);
            notice.initOwner(mainStage);
            notice.setTitle("PDF 내보내기");
            notice.setHeaderText("보고서를 PDF로 저장했습니다.");
            notice.setContentText(destination.toAbsolutePath().toString());
            notice.getDialogPane().setId("pdf-export-success");
            notice.getDialogPane().getStylesheets().add(MainApp.class.getResource("app.css").toExternalForm());
            ButtonType open = new ButtonType("PDF 열기", ButtonBar.ButtonData.OTHER);
            notice.getButtonTypes().setAll(open, ButtonType.OK);
            notice.setOnHidden(closed -> {
                if (notice.getResult() == open) {
                    try {
                        if (!Desktop.isDesktopSupported()) throw new IOException("이 환경에서는 PDF 자동 열기를 지원하지 않습니다.");
                        Desktop.getDesktop().open(destination.toFile());
                    } catch (IOException | UnsupportedOperationException error) {
                        error("PDF 열기 실패", error.getMessage());
                    }
                }
            });
            notice.show();
        });
        task.setOnFailed(event -> {
            pdfExporting.set(false);
            if (!busy.get()) status.setText("PDF 저장 실패 · 다시 시도하세요");
            Throwable failure = task.getException();
            error("PDF 내보내기 실패", failure.getMessage() == null ? failure.toString() : failure.getMessage());
        });
        Thread exporter = new Thread(task, "pdf-report-export");
        exporter.setDaemon(true);
        exporter.start();
    }

    private void save(Stage stage, String defaultName, String content) {
        FileChooser chooser = new FileChooser();
        chooser.setInitialFileName(defaultName);
        var file = chooser.showSaveDialog(stage);
        if (file == null) {
            return;
        }
        try {
            Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
            status.setText("저장됨 · " + file.getName());
        } catch (IOException e) {
            error("저장 실패", e.getMessage());
        }
    }

    private void shutdown() {
        stop();
        Platform.exit();
    }

    @Override
    public void stop() {
        if (opening != null) opening.close();
        if (!closing.compareAndSet(false, true)) return;
        Platform.getPreferences().reducedMotionProperty().removeListener(systemMotion);
        cancelRequested.set(true);
        stopCliProbe();
        activity.stop();
        worker.submit(() -> {
            if (llmRuntime != null) {
                llmRuntime.close();
            }
        });
        worker.shutdown();
        libraryWorker.shutdown();
    }

    // ------------------------------------------------------------------ state

    private Mode selectedMode() {
        var t = modeGroup.getSelectedToggle();
        return t == null ? Mode.RESEARCH : (Mode) t.getUserData();
    }

    private void updateModeCaption() {
        modeCaption.setText(switch (selectedMode()) {
            case FAST -> "기본 렌즈 5개로 빠르게 가설을 세웁니다.";
            case RESEARCH -> "질문 유형을 분류해 알맞은 렌즈 5~7개를 고릅니다.";
            case COUNCIL -> "15개 렌즈를 모두 사용합니다. 로컬 LLM에서는 오래 걸립니다.";
        });
    }

    private void refreshEngine() {
        boolean exists = Files.isRegularFile(modelPath);
        modelName.setText(modelPath.getFileName().toString());
        modelStatus.setText(exists ? "✓ 준비됨 · " + String.format("%.1f GB", sizeGb(modelPath)) : "! 파일 없음 · 아래에서 다운로드하세요");
        modelStatus.getStyleClass().setAll("label", exists ? "status-ok" : "status-warn");
        Tooltip.install(modelName, new Tooltip(modelPath.toString()));
        engineChip.getStyleClass().removeAll("engine-ready", "engine-warning");
        String iconStyle;
        if (engineCli.isSelected()) {
            String checking = cliProbeThread == null ? "미확인" : "확인 중";
            engineChip.setText("엔진: " + cliProvider.getValue().name() + " ("
                    + (cliAvailable == null ? checking : cliAvailable ? "감지됨" : "미감지") + ")");
            iconStyle = Boolean.TRUE.equals(cliAvailable) ? "icon-success" : "icon-warn";
        } else if (engineLlm.isSelected()) {
            engineChip.setText("엔진: 로컬 LLM (" + (exists ? "준비됨" : "모델 없음") + ")");
            iconStyle = exists ? "icon-success" : "icon-warn";
        } else {
            engineChip.setText("엔진: Mock (화면 테스트)");
            iconStyle = "icon-primary";
        }
        engineChip.setGraphic(WorkbenchUi.icon("mdi2c-circle", "engine-dot", iconStyle));
        engineChip.setTooltip(new Tooltip(engineCli.isSelected()
                ? "CLI 실행 파일 감지 상태입니다. 로그인 여부는 실제 실행에서 확인합니다."
                : engineLlm.isSelected() ? modelPath.toString() : "고정 템플릿을 사용하는 테스트 엔진"));

    }

    /** Repopulates the model dropdown for the chosen CLI and reports whether it is installed. */
    private void onCliProviderChanged(CliProvider provider) {
        if (provider == null) {
            return;
        }
        String previous = cliModel.getValue();
        cliModel.getItems().setAll(provider.models());
        cliModel.setValue(previous != null && provider.models().contains(previous) ? previous : provider.defaultModel());
        cliSettings = cliSettings.with(provider, cliModel.getValue());
        probeCli(provider);
    }

    private void probeCli(CliProvider provider) {
        stopCliProbe();
        cliAvailable = null;
        cliStatus.setText("설치 확인 중…");
        cliStatus.getStyleClass().setAll("label", "caption");
        cliProbeThread = Thread.ofVirtual().name("cli-installation-check").start(() -> {
            Thread checker = Thread.currentThread();
            String issue = cliInstallationIssue(provider);
            Platform.runLater(() -> {
                if (cliProbeThread != checker || closing.get()) return;
                cliProbeThread = null;
                boolean installed = issue == null;
                cliAvailable = installed;
                cliStatus.setText(installed ? "✓ " + provider.executable() + " 감지됨" : "! " + issue);
                cliStatus.getStyleClass().setAll("label", installed ? "status-ok" : "status-warn");
                refreshEngine();
                if (waitingForCli) {
                    waitingForCli = false;
                    if (installed) {
                        busy.set(false);
                        runAnalysis();
                    } else {
                        finish("CLI 확인 실패");
                        error("CLI 확인 실패", issue);
                    }
                }
            });
        });
        if (engineChip.getScene() != null) {
            refreshEngine();
        }
    }

    private static String cliInstallationIssue(CliProvider provider) {
        try {
            return CliEngine.isInstalled(provider) ? null : provider.executable()
                    + " 실행 파일을 확인하지 못했습니다. CLI를 설치하거나 설정에서 다른 분석 엔진을 선택하세요.";
        } catch (RuntimeException e) {
            return provider.executable() + " 설치 확인 오류 · " + (e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private void stopCliProbe() {
        if (cliProbeThread != null) {
            cliProbeThread.interrupt();
            cliProbeThread = null;
        }
    }

    private static double sizeGb(Path p) {
        try {
            return Files.size(p) / 1e9;
        } catch (IOException e) {
            return 0;
        }
    }

    private void bindTask(Task<?> task) {
        busy.set(true);
        progress.progressProperty().bind(task.progressProperty());
        status.textProperty().bind(task.messageProperty());
    }

    private void finish(String message) {
        progress.progressProperty().unbind();
        status.textProperty().unbind();
        status.setText(message);
        busy.set(false);
        runButton.setText(lastResult == null ? "분석 실행" : "다시 분석");
    }

    private void stopped(String message) {
        activity.finish(message);
        stages.markStopped();
        workflow.stop();
        results.stopped(message);
        progress.progressProperty().unbind();
        progress.setProgress(0);
        finish(message);
    }

    // ------------------------------------------------------------------ widgets

    private static VBox card(String title, Node... children) {
        VBox card = new VBox(10);
        card.getStyleClass().add("card");
        card.getChildren().add(ResultView.text(title.toUpperCase(), "card-title"));
        card.getChildren().addAll(children);
        return card;
    }

    private static ToggleButton segment(String text, ToggleGroup group) {
        ToggleButton b = new ToggleButton(text);
        b.getStyleClass().add("segment");
        b.setMaxWidth(Double.MAX_VALUE);
        b.setToggleGroup(group);
        b.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            if (group.getSelectedToggle() == null) {
                b.setSelected(true);
            }
        });
        // keep exactly one selected
        b.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
            if (b.isSelected()) {
                e.consume();
            }
        });
        return b;
    }

    /** Rounds the outer corners of a segmented control. */
    private static void roundSegments(HBox box) {
        var items = box.getChildren();
        for (int i = 0; i < items.size(); i++) {
            String radius = items.size() == 1 ? "8"
                    : i == 0 ? "8 0 0 8" : i == items.size() - 1 ? "0 8 8 0" : "0";
            items.get(i).setStyle("-fx-background-radius: " + radius + "; -fx-border-radius: " + radius + ";");
        }
    }

    private static Button iconButton(String icon, String label) {
        Button button = new Button();
        button.setGraphic(WorkbenchUi.icon(icon, "icon-muted"));
        button.getStyleClass().add("icon-button");
        button.setAccessibleText(label);
        button.setTooltip(new Tooltip(label));
        return button;
    }

    private static Button secondary(String text) {
        Button b = new Button(text);
        b.getStyleClass().add("secondary-button");
        return b;
    }

    private static Button link(String text) {
        Button b = new Button(text);
        b.getStyleClass().add("link-button");
        return b;
    }

    private static <T extends Node> T styled(T node, String style) {
        node.getStyleClass().add(style);
        return node;
    }

    private void error(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(mainStage);
        alert.getDialogPane().getStylesheets().add(MainApp.class.getResource("app.css").toExternalForm());
        alert.setHeaderText(title);
        TextArea detail = new TextArea(message);
        detail.setEditable(false);
        detail.setWrapText(true);
        detail.setPrefRowCount(8);
        detail.setMaxWidth(Double.MAX_VALUE);
        alert.getDialogPane().setExpandableContent(detail);
        alert.getDialogPane().setExpanded(true);
        alert.getDialogPane().setContentText(message != null && message.length() > 120 ? message.substring(0, 120) + "…" : message);
        alert.showAndWait();
    }
}
