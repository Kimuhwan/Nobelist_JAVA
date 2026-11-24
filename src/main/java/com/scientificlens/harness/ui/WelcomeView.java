package com.scientificlens.harness.ui;

import com.scientificlens.harness.domain.Mode;
import java.util.List;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;

final class WelcomeView extends ScrollPane {
    private final TextArea input = new TextArea();

    WelcomeView(StringProperty question, ToggleGroup modes, BooleanProperty busy,
            BooleanProperty hasResult, Button run, Runnable workspace, Runnable library) {
        setId("welcome-view");
        getStyleClass().addAll("welcome-scroll", "page-scroll");
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        VBox content = new VBox(28);
        content.getStyleClass().add("welcome-content");
        content.setMaxWidth(1280);
        content.setMinWidth(0);

        Label eyebrow = ResultView.text("SCIENTIFIC LENS HARNESS  /  RESEARCH STUDIO", "welcome-eyebrow");
        Label title = ResultView.text("좋은 질문이,\n다음 실험이 되는 곳.", "welcome-title");
        Label intro = ResultView.text("아이디어를 여러 관점으로 살펴보고,\n반론을 거쳐 검증 가능한 첫 실험을 설계하세요.", "welcome-intro");
        HBox proof = new HBox(16, smallFact("mdi2a-account-group-outline", "15개 방법론 렌즈"),
                smallFact("mdi2f-flask-outline", "가설에서 실험까지"));
        proof.setAlignment(Pos.CENTER_LEFT);
        VBox heading = new VBox(16, eyebrow, title, intro, proof);
        heading.setMinWidth(0);
        heading.setAlignment(Pos.CENTER_LEFT);
        Pane constellation = new WelcomeConstellation();
        GridPane hero = new GridPane();
        hero.getStyleClass().add("welcome-hero");
        ColumnConstraints left = new ColumnConstraints();
        left.setPercentWidth(55); left.setMinWidth(0);
        ColumnConstraints right = new ColumnConstraints();
        right.setPercentWidth(45); right.setMinWidth(0);
        hero.getColumnConstraints().setAll(left, right);
        hero.add(heading, 0, 0); hero.add(constellation, 1, 0);
        content.getChildren().add(hero);

        input.setId("welcome-question");
        input.setPromptText("어떤 아이디어를 검증하고 싶으세요?");
        input.setWrapText(true);
        input.setPrefRowCount(3);
        input.setMinHeight(112); input.setPrefHeight(112); input.setMaxHeight(112);
        input.setAccessibleText("검증할 질문 또는 아이디어. Ctrl+Enter로 연구 시작");
        input.textProperty().bindBidirectional(question);
        input.disableProperty().bind(busy);
        input.getStyleClass().add("welcome-input");
        Button start = new Button("", WorkbenchUi.icon("mdi2a-arrow-right"));
        start.textProperty().bind(Bindings.when(busy).then(run.textProperty()).otherwise("연구 시작하기"));
        start.setId("welcome-start");
        start.getStyleClass().addAll("primary-button", "welcome-start");
        start.setOnAction(e -> run.fire());
        start.disableProperty().bind(run.disabledProperty());
        HBox choices = new HBox(4);
        choices.getStyleClass().add("welcome-modes");
        for (Mode mode : Mode.values()) {
            ToggleButton button = new ToggleButton(switch (mode) {
                case FAST -> "빠르게 탐색";
                case RESEARCH -> "깊이 있는 연구";
                case COUNCIL -> "전체 렌즈";
            });
            button.setId("welcome-mode-" + mode.value());
            button.getStyleClass().add("welcome-mode");
            button.setAccessibleText("분석 모드 " + button.getText());
            button.disableProperty().bind(busy);
            Runnable sync = () -> button.setSelected(modes.getSelectedToggle() != null
                    && modes.getSelectedToggle().getUserData() == mode);
            modes.selectedToggleProperty().addListener((o, before, after) -> sync.run());
            sync.run();
            button.setOnAction(e -> {
                modes.getToggles().stream().filter(t -> t.getUserData() == mode)
                        .findFirst().ifPresent(modes::selectToggle);
                sync.run();
            });
            choices.getChildren().add(button);
        }
        HBox actions = new HBox(16, choices, WorkbenchUi.spacer(),
                ResultView.text("Ctrl + Enter", "welcome-shortcut"), start);
        actions.setAlignment(Pos.CENTER_LEFT);
        VBox composer = new VBox(12, input, WorkbenchUi.divider(), actions);
        composer.getStyleClass().add("welcome-composer");
        content.getChildren().add(composer);

        HBox suggestionHeading = new HBox(8, ResultView.text("이런 질문으로 시작해 보세요", "welcome-section-label"),
                WorkbenchUi.spacer(), ResultView.text("예시를 누르면 입력창에 담깁니다", "caption"));
        GridPane examples = new GridPane();
        examples.setHgap(12); examples.setMinWidth(0);
        List<String> questions = List.of("AI 설계 지원이 초보자의\n아두이노 제작 시간을 줄일까?",
                "농산물 폐기량을 줄이는\n서비스는 어떻게 검증할까?",
                "창고 센서의 전력 사용을 줄이면\n온도 관리 품질은 유지될까?");
        List<String> tags = List.of("제품 아이디어", "비즈니스 가설", "기술 실험");
        List<String> icons = List.of("mdi2l-lightbulb-outline", "mdi2s-sprout-outline", "mdi2a-access-point");
        for (int i = 0; i < questions.size(); i++) {
            String value = questions.get(i).replace('\n', ' ');
            Label tag = ResultView.text(tags.get(i), "welcome-example-tag");
            VBox copy = new VBox(8, tag, ResultView.text(questions.get(i), "welcome-example-text"));
            HBox body = new HBox(12, WorkbenchUi.icon(icons.get(i), "icon-primary"), copy,
                    WorkbenchUi.spacer(), WorkbenchUi.icon("mdi2a-arrow-top-right", "icon-small"));
            body.setAlignment(Pos.CENTER_LEFT);
            Button button = new Button();
            button.setId("welcome-example-" + i);
            button.disableProperty().bind(busy);
            button.setGraphic(body); button.setAccessibleText(tags.get(i) + ": " + value);
            button.getStyleClass().add("welcome-example");
            button.setMaxWidth(Double.MAX_VALUE);
            button.setOnAction(e -> { question.set(value); input.requestFocus(); input.positionCaret(value.length()); });
            ColumnConstraints column = new ColumnConstraints();
            column.setPercentWidth(100.0 / 3); column.setMinWidth(0);
            examples.getColumnConstraints().add(column);
            examples.add(button, i, 0);
        }
        content.getChildren().add(new VBox(12, suggestionHeading, examples));

        Button open = new Button("작업 공간 열기", WorkbenchUi.icon("mdi2v-view-dashboard-outline", "icon-small"));
        open.setId("welcome-workspace"); open.getStyleClass().add("link-button");
        open.textProperty().bind(Bindings.when(hasResult)
                .then("이전 분석으로 돌아가기").otherwise("작업 공간 열기"));
        open.setOnAction(e -> workspace.run());
        Button saved = new Button("연구 보관함", WorkbenchUi.icon("mdi2b-bookshelf", "icon-small"));
        saved.setId("welcome-library"); saved.getStyleClass().add("link-button"); saved.setOnAction(e -> library.run());
        HBox footer = new HBox(16, ResultView.text("과학자의 사고방식을 빌린 방법론입니다. 인물의 생각을 재현하지 않습니다.", "welcome-note"),
                WorkbenchUi.spacer(), open, saved);
        footer.setAlignment(Pos.CENTER_LEFT);
        content.getChildren().add(footer);
        StackPane container = new StackPane(content);
        container.getStyleClass().add("welcome-container");
        container.setAlignment(Pos.TOP_CENTER);
        setContent(container);
        widthProperty().addListener((o, before, now) -> {
            boolean compact = now.doubleValue() < 1200;
            title.getStyleClass().remove("welcome-title-compact");
            if (compact) title.getStyleClass().add("welcome-title-compact");
        });
    }

    void focusInput() { input.requestFocus(); }

    private static HBox smallFact(String icon, String value) {
        HBox row = new HBox(8, WorkbenchUi.icon(icon, "icon-small", "icon-primary"),
                ResultView.text(value, "welcome-fact"));
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static final class WelcomeConstellation extends Pane {
        private final Circle outer = new Circle();
        private final Circle inner = new Circle();
        private final VBox center;
        private final List<Button> nodes;
        private final Label explanation = ResultView.text("렌즈를 눌러 관점을 살펴보세요", "welcome-map-note");
        WelcomeConstellation() {
            setId("welcome-constellation");
            getStyleClass().add("welcome-constellation");
            setMinHeight(284); setPrefHeight(304);
            outer.getStyleClass().add("welcome-orbit"); inner.getStyleClass().add("welcome-orbit-inner");
            outer.setMouseTransparent(true); inner.setMouseTransparent(true);
            center = new VBox(8, WorkbenchUi.icon("mdi2a-atom", "welcome-core-icon"),
                    ResultView.text("하나의 질문,\n다양한 관점.", "welcome-map-core"));
            center.getStyleClass().add("welcome-core"); center.setAlignment(Pos.CENTER);
            List<String> names = List.of("Newton", "Curie", "Tesla", "Pasteur", "Feynman");
            List<String> methods = List.of("원인과 변수", "데이터와 증거", "구현과 시스템", "대조 실험", "명료한 설명");
            List<String> codes = List.of("mdi2s-source-branch", "mdi2c-chart-scatter-plot", "mdi2f-flash-outline",
                    "mdi2f-flask-outline", "mdi2l-lightbulb-outline");
            nodes = new java.util.ArrayList<>();
            getChildren().addAll(outer, inner, center);
            for (int i = 0; i < names.size(); i++) {
                final int index = i;
                VBox face = new VBox(4, WorkbenchUi.icon(codes.get(i), "icon-primary"),
                        ResultView.text(names.get(i), "welcome-map-name"));
                face.setAlignment(Pos.CENTER);
                Button node = new Button(); node.setGraphic(face);
                node.setId("welcome-lens-" + names.get(i).toLowerCase(java.util.Locale.ROOT));
                node.getStyleClass().add("welcome-lens");
                node.setAccessibleText(names.get(i) + " 렌즈: " + methods.get(i));
                node.setOnAction(e -> {
                    nodes.forEach(n -> n.getStyleClass().remove("welcome-lens-selected"));
                    node.getStyleClass().add("welcome-lens-selected");
                    explanation.setText(names.get(index) + " · " + methods.get(index));
                });
                nodes.add(node); getChildren().add(node);
            }
            getChildren().add(explanation);
        }
        @Override protected void layoutChildren() {
            double width = getWidth(), height = getHeight();
            double cx = width * .5, cy = height * .46;
            double rx = Math.min(184, width * .36), ry = 108;
            outer.setCenterX(cx); outer.setCenterY(cy); outer.setRadius(Math.min(rx, 148));
            outer.setScaleX(rx / outer.getRadius()); outer.setScaleY(ry / outer.getRadius());
            inner.setCenterX(cx); inner.setCenterY(cy); inner.setRadius(77);
            center.resizeRelocate(cx - 66, cy - 62, 132, 124);
            for (int i = 0; i < nodes.size(); i++) {
                double angle = Math.toRadians(-90 + i * 72);
                nodes.get(i).resizeRelocate(cx + rx * Math.cos(angle) - 44,
                        cy + ry * Math.sin(angle) - 32, 88, 64);
            }
            explanation.resizeRelocate(0, height - 24, width, 24);
        }
    }
}
