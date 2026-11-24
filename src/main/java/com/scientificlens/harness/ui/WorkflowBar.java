package com.scientificlens.harness.ui;

import java.util.List;
import java.util.function.IntConsumer;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.beans.binding.Bindings;
import javafx.scene.layout.VBox;

/** The four user-facing phases, driven by actual orchestrator progress. */
final class WorkflowBar extends HBox {
    private static final List<String> ICONS = List.of(
            "mdi2l-lightbulb-outline", "mdi2f-format-list-bulleted",
            "mdi2s-shield-outline", "mdi2f-flask-outline");
    private static final List<String> TITLES = List.of("아이디어", "렌즈 분석", "반론 · 레드팀", "실험 계획");
    private static final List<String> CAPTIONS = List.of(
            "질문을 분석하고 문제를 정의", "다양한 관점에서 분석",
            "약점과 위험 요소 검토", "가설을 실험으로 전환");

    private final Button[] buttons = new Button[4];
    private final Label[] titles = new Label[4];
    private final Label[] captions = new Label[4];
    private final Label[] badges = new Label[4];
    private int lenses = 7;
    private int phase;
    private int viewed;
    private boolean running;
    private boolean complete;
    private boolean stopped;

    WorkflowBar(IntConsumer navigate) {
        super(12);
        getStyleClass().add("workflow-bar");
        setAlignment(Pos.CENTER_LEFT);
        for (int i = 0; i < buttons.length; i++) {
            final int page = i;
            titles[i] = ResultView.text((i + 1) + ". " + TITLES.get(i), "workflow-title");
            captions[i] = ResultView.text(CAPTIONS.get(i), "workflow-caption");
            badges[i] = WorkbenchUi.badge("완료", "workflow-done-badge");
            badges[i].setGraphic(WorkbenchUi.icon("mdi2c-check-circle", "icon-success"));
            HBox title = new HBox(6, titles[i], WorkbenchUi.spacer(), badges[i]);
            title.setAlignment(Pos.CENTER_LEFT);
            VBox copy = new VBox(4, title, captions[i]);
            copy.setMinWidth(0);
            HBox.setHgrow(copy, Priority.ALWAYS);
            HBox content = new HBox(12, WorkbenchUi.icon(ICONS.get(i), "workflow-icon"), copy);
            content.setAlignment(Pos.CENTER_LEFT);
            Button button = new Button();
            button.setId("workflow-" + i);
            button.setGraphic(content);
            button.setContentDisplay(javafx.scene.control.ContentDisplay.GRAPHIC_ONLY);
            content.prefWidthProperty().bind(Bindings.createDoubleBinding(
                    () -> Math.max(0, button.getWidth() - button.getInsets().getLeft() - button.getInsets().getRight()),
                    button.widthProperty(), button.insetsProperty()));
            content.setMinWidth(0);
            content.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
            button.prefWidthProperty().bind(Bindings.createDoubleBinding(
                    () -> Math.max(0, (getWidth() - getInsets().getLeft() - getInsets().getRight()
                            - 72 - getSpacing() * 6) / 4),
                    widthProperty(), insetsProperty(), spacingProperty()));
            button.setMinWidth(0);
            button.setMaxWidth(Double.MAX_VALUE);
            button.getStyleClass().add("workflow-step");
            button.setOnAction(e -> navigate.accept(page));
            HBox.setHgrow(button, Priority.ALWAYS);
            buttons[i] = button;
            getChildren().add(button);
            if (i < buttons.length - 1) {
                getChildren().add(WorkbenchUi.icon("mdi2a-arrow-right", "workflow-arrow"));
            }
        }
        widthProperty().addListener((obs, old, now) -> refresh());
        refresh();
    }

    void setLensCount(int count) {
        lenses = count;
        titles[1].setText(count == 0 ? "2. 렌즈 선택 대기" : "2. " + count + "개 렌즈");
        refresh();
    }

    void begin(int count) {
        setLensCount(count);
        running = true;
        complete = false;
        stopped = false;
        phase = count == 0 ? 0 : 1;
        viewed = 0;
        refresh();
    }

    void update(int done) {
        phase = lenses == 0 ? 0 : done < lenses ? 1 : done < lenses + 2 ? 2 : 3;
        complete = lenses > 0 && done >= lenses + 4;
        refresh();
    }

    void finish() {
        complete = true;
        running = false;
        stopped = false;
        viewed = 3;
        phase = 3;
        refresh();
    }

    void stop() {
        running = false;
        stopped = true;
        refresh();
    }

    void showPage(int page) {
        viewed = page == 0 && complete ? 3 : Math.min(page, 3);
        refresh();
    }

    private void refresh() {
        for (int i = 0; i < buttons.length; i++) {
            boolean done = complete || running && i < phase || stopped && i < phase;
            boolean active = running ? i == phase : i == viewed;
            buttons[i].getStyleClass().removeAll("workflow-active", "workflow-done", "workflow-stopped");
            if (done) {
                buttons[i].getStyleClass().add("workflow-done");
            }
            if (active) {
                buttons[i].getStyleClass().add("workflow-active");
            }
            if (stopped && i == phase) {
                buttons[i].getStyleClass().add("workflow-stopped");
            }
            boolean compact = getWidth() > 0 && getWidth() < 1200;
            badges[i].setText(compact ? "" : "완료");
            captions[i].setVisible(!compact);
            captions[i].setManaged(!compact);
            buttons[i].pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("compact"), compact);
            badges[i].setVisible(done);
            badges[i].setManaged(done);
            captions[i].setText(stopped && i == phase ? "분석이 중단되었습니다"
                    : running && lenses == 0 && i == phase ? "문제 분석 · 렌즈 선택 중…"
                    : running && i == phase ? "분석 중…" : CAPTIONS.get(i));
            buttons[i].setAccessibleText(TITLES.get(i) + ", "
                    + (stopped && i == phase ? "중단" : done ? "완료" : running && i == phase ? "진행 중" : "대기")
                    + ", 상세 보기");

        }
    }
}

