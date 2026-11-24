package com.scientificlens.harness.ui;

import com.scientificlens.harness.core.Reporting;
import com.scientificlens.harness.domain.AgentEvent;
import com.scientificlens.harness.domain.HarnessResult;
import java.util.Locale;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

final class ActivityView extends BorderPane {
    private final VBox entries = new VBox(16);
    private final ScrollPane scroll = new ScrollPane(entries);
    private final Label phase = ResultView.text("분석을 시작하면 실제 작업 기록이 여기에 쌓입니다.", "field-value");
    private final Label elapsed = ResultView.text("대기 중", "caption");
    private final Label provenance = ResultView.text("공개 작업 지시와 답변 · 내부 추론은 포함하지 않습니다.", "caption");
    private final CheckBox follow = new CheckBox("새 기록 따라가기");
    private final CheckBox raw = new CheckBox("원본 출력 보기");
    private final ToggleButton transcript = new ToggleButton("전체 기록");
    private final CouncilStageView council = new CouncilStageView();
    private final TextArea log;
    private final Timeline timer;
    private long started;

    ActivityView(TextArea log) {
        this.log = log;
        setId("activity-view");
        getStyleClass().add("activity-view");
        entries.setMinWidth(0);
        entries.getStyleClass().add("activity-entries");
        scroll.setId("activity-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("page-scroll");
        phase.setId("activity-phase");
        elapsed.setId("activity-elapsed");
        follow.setId("activity-follow");
        raw.setId("activity-raw");
        follow.setSelected(true);
        transcript.setId("activity-transcript");
        transcript.getStyleClass().add("reader-toggle");
        transcript.setGraphic(WorkbenchUi.icon("mdi2f-format-list-bulleted", "icon-small"));
        transcript.textProperty().bind(javafx.beans.binding.Bindings.when(transcript.selectedProperty())
                .then("토론 무대로").otherwise("전체 기록"));
        follow.visibleProperty().bind(transcript.selectedProperty());
        follow.managedProperty().bind(follow.visibleProperty());
        HBox controls = new HBox(16, elapsed, transcript, follow, raw);
        controls.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox heading = new HBox(16, WorkbenchUi.heading("mdi2a-account-group-outline", "연구 진행"),
                WorkbenchUi.spacer(), controls);
        heading.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        VBox header = new VBox(8, heading, phase, provenance);
        header.getStyleClass().add("activity-header");
        setTop(header);
        ScrollPane councilScroll = new ScrollPane(council);
        councilScroll.setId("council-scroll");
        councilScroll.setFitToWidth(true);
        councilScroll.setFitToHeight(true);
        councilScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        councilScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        councilScroll.setMinSize(0, 0);
        councilScroll.getStyleClass().add("council-scroll");
        StackPane surfaces = new StackPane(councilScroll, scroll, log);
        councilScroll.visibleProperty().bind(raw.selectedProperty().not().and(transcript.selectedProperty().not()));
        scroll.visibleProperty().bind(raw.selectedProperty().not().and(transcript.selectedProperty()));
        log.visibleProperty().bind(raw.selectedProperty());
        for (var node : surfaces.getChildren()) node.managedProperty().bind(node.visibleProperty());
        setCenter(surfaces);
        follow.selectedProperty().addListener((o, before, now) -> { if (now) followLatest(); });
        timer = new Timeline(new KeyFrame(Duration.seconds(1), e -> updateElapsed()));
        timer.setCycleCount(Timeline.INDEFINITE);
        empty("아직 작업 기록이 없습니다", "질문을 입력하고 분석을 실행하세요.");
    }

    void begin(boolean mock) {
        timer.stop();
        started = System.nanoTime();
        raw.setSelected(false);
        follow.setSelected(true);
        transcript.setSelected(false);
        council.begin(mock);
        phase.setText("분석 준비 중");
        provenance.setText(mock ? "Mock 테스트 · 고정 템플릿 분석입니다. 실제 에이전트 호출은 수행하지 않습니다."
                : "진행 중인 실제 호출 · 공개 작업 지시와 답변만 표시합니다.");
        empty("첫 작업을 기다리고 있습니다", "현재 단계와 경과 시간을 확인할 수 있습니다. 완료 시간을 예측하지 않습니다.");
        updateElapsed();
        timer.play();
    }

    void stage(String message) { phase.setText(message); council.stage(message); }

    void selectLenses(java.util.List<String> lensIds) { council.selectLenses(lensIds); }

    void setReducedMotion(boolean reduced) { council.setReducedMotion(reduced); }

    void add(AgentEvent event) {
        council.add(event);
        if (entries.getChildren().size() == 1 && "activity-empty".equals(entries.getChildren().getFirst().getId())) {
            entries.getChildren().clear();
        }
        Label route = ResultView.text(String.format(Locale.ROOT, "%02d", event.sequence()) + "  /  "
                + Reporting.agentLabel(event.actor()) + " → " + Reporting.agentLabel(event.recipient()), "debate-route");
        TitledPane body = WorkbenchUi.disclosure("공개 기록 전문 보기", ResultView.text(event.content(), "field-value"));
        body.setId("activity-event-" + event.sequence());
        VBox card = WorkbenchUi.panel("activity-entry");
        card.getChildren().addAll(route, ResultView.text(Reporting.actionLabel(event.action()), "field-title"), body);
        entries.getChildren().add(card);
        followLatest();
    }

    void finish(String message) {
        timer.stop();
        updateElapsed();
        phase.setText(message);
        council.finish(message);
        if (entries.getChildren().size() == 1 && "activity-empty".equals(entries.getChildren().getFirst().getId())) {
            empty(message, "공개 에이전트 기록이 없습니다. 원본 출력에서 단계별 진행 기록을 확인할 수 있습니다.");
        }
    }

    void replay(HarnessResult result, String savedAt) {
        timer.stop();
        raw.setSelected(false);
        follow.setSelected(false);
        transcript.setSelected(false);
        entries.getChildren().clear();
        phase.setText("저장된 연구 보고서 · 실행 완료 기록");
        elapsed.setText("저장 시각 " + AnalysisLibraryView.timeLabel(savedAt));
        provenance.setText("저장된 공개 기록을 다시 보고 있습니다. 현재 실행 중인 분석이 아닙니다.");
        for (var event : result.agentEvents()) add(event);
        council.replay(result);
        if (result.agentEvents().isEmpty()) empty("이 보고서에 공개 호출 기록이 없습니다", "이전 형식 또는 Mock 보고서입니다. 분석 결과는 다른 탭에서 확인하세요.");
        log.setText("저장된 보고서에는 원본 프로세스 출력이 보관되지 않습니다.\n공개 호출 기록은 연구 진행 화면, 전체 분석 데이터는 JSON 탭에서 확인하세요.");
        scroll.setVvalue(0);
    }

    void stop() { timer.stop(); council.stop(); }

    private void empty(String title, String helper) {
        VBox box = new VBox(12, ResultView.text(title, "section-heading"), ResultView.text(helper, "caption"));
        box.setId("activity-empty");
        box.getStyleClass().add("activity-empty");
        entries.getChildren().setAll(box);
    }

    private void updateElapsed() {
        long seconds = Math.max(0, (System.nanoTime() - started) / 1_000_000_000L);
        elapsed.setText(String.format(Locale.ROOT, "경과 %02d:%02d", seconds / 60, seconds % 60));
    }

    private void followLatest() {
        if (follow.isSelected()) Platform.runLater(() -> scroll.setVvalue(1));
    }
}
