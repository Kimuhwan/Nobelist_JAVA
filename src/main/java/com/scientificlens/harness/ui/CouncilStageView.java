package com.scientificlens.harness.ui;

import com.scientificlens.harness.core.LensRegistry;
import com.scientificlens.harness.core.Reporting;
import com.scientificlens.harness.domain.AgentEvent;
import com.scientificlens.harness.domain.HarnessResult;
import com.scientificlens.harness.domain.LensProfile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.PathTransition;
import javafx.beans.InvalidationListener;
import javafx.beans.value.ChangeListener;
import javafx.css.PseudoClass;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Ellipse;
import javafx.scene.shape.Line;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.scene.shape.QuadCurveTo;
import javafx.stage.Window;
import javafx.util.Duration;

public final class CouncilStageView extends BorderPane {
    private static final PseudoClass ACTIVE = PseudoClass.getPseudoClass("active");
    private static final PseudoClass COMPLETE = PseudoClass.getPseudoClass("complete");
    private static final PseudoClass INSPECTED = PseudoClass.getPseudoClass("inspected");
    private static final PseudoClass DENSE = PseudoClass.getPseudoClass("dense");
    private static final PseudoClass COMPACT = PseudoClass.getPseudoClass("compact");
    private static final Duration ACTIVE_PULSE = Duration.millis(1600);
    private static final Duration ROUTE_TRANSFER = Duration.millis(700);

    private final Map<String, LensProfile> catalog = new LinkedHashMap<>();
    private final Map<String, AgentNode> nodes = new LinkedHashMap<>();
    private final Set<String> selected = new LinkedHashSet<>();
    private final Set<String> acceptedAnalyses = new LinkedHashSet<>();
    private final List<AgentEvent> events = new ArrayList<>();
    private final List<Parent> observedAncestors = new ArrayList<>();
    private final Constellation graph = new Constellation();
    private final VBox detail = new VBox(12);
    private final ScrollPane detailScroll = new ScrollPane(detail);
    private final Label current = label("질문을 시작하면 실제 연구팀이 구성됩니다.", "council-current");
    private final Label count = label("선택 전 · 공개 기록 0", "council-count");
    private final Label provenance = label("방법론적 렌즈 · 실제 인물의 생각을 재현하지 않습니다.", "council-provenance");
    private final Button followCurrent = new Button("현재 작업");
    private final InvalidationListener visibilityListener = ignored -> updateMotion();
    private final ChangeListener<Window> windowListener = (o, before, now) -> observeWindow(now);
    private Window observedWindow;
    private FadeTransition pulse;
    private PathTransition transfer;
    private String inspectedId = "coordinator";
    private String activeId;
    private String routeFrom;
    private String routeTo;
    private boolean pinned;
    private boolean running;
    private boolean mock;
    private boolean saved;
    private boolean replaying;
    private boolean reducedMotion;

    public CouncilStageView() {
        LensRegistry.load().forEach(profile -> catalog.put(profile.lensId(), profile));
        setId("council-stage");
        getStyleClass().add("council-stage");
        var css = CouncilStageView.class.getResource("council-stage.css");
        if (css != null) getStylesheets().add(css.toExternalForm());
        setMinWidth(0);
        setMaxWidth(Double.MAX_VALUE);
        current.setId("council-current");
        count.setId("council-count");
        current.setMaxWidth(Double.MAX_VALUE);
        count.setMinWidth(Region.USE_PREF_SIZE);
        followCurrent.getStyleClass().add("council-follow");
        followCurrent.setAccessibleText("최신 공개 작업을 따라가며 노드 살펴보기");
        followCurrent.setOnAction(e -> {
            pinned = false;
            inspect(activeId != null ? activeId : "coordinator", false);
        });
        HBox topline = new HBox(12, label("RESEARCH COUNCIL", "council-eyebrow"), spacer(), count);
        topline.setAlignment(Pos.CENTER_LEFT);
        HBox statusline = new HBox(12, current, followCurrent);
        statusline.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(current, Priority.ALWAYS);
        VBox header = new VBox(8, topline, statusline);
        header.getStyleClass().add("council-header");
        setTop(header);

        detail.setId("council-detail");
        detail.getStyleClass().add("council-detail");
        detail.setMinWidth(0);
        detailScroll.setId("council-detail-scroll");
        detailScroll.getStyleClass().add("council-detail-scroll");
        detailScroll.setFitToWidth(true);
        detailScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        detailScroll.setMinWidth(260);
        detailScroll.setPrefWidth(340);
        detailScroll.setMaxWidth(380);
        detailScroll.setPrefHeight(304);
        detailScroll.setMinHeight(260);
        detailScroll.setMaxHeight(Double.MAX_VALUE);
        graph.setPrefHeight(304);
        graph.setMinHeight(260);
        graph.setMaxHeight(Double.MAX_VALUE);
        graph.setMinWidth(0);
        HBox body = new HBox(graph, detailScroll);
        body.getStyleClass().add("council-body");
        HBox.setHgrow(graph, Priority.ALWAYS);
        setCenter(body);
        provenance.getStyleClass().add("council-footer");
        setBottom(provenance);

        ensureNode("coordinator");
        inspect("coordinator", false);
        sceneProperty().addListener((o, before, now) -> {
            if (before != null) before.windowProperty().removeListener(windowListener);
            if (now != null) now.windowProperty().addListener(windowListener);
            observeWindow(now == null ? null : now.getWindow());
            observeAncestors();
        });
        parentProperty().addListener((o, before, now) -> observeAncestors());
        visibleProperty().addListener(visibilityListener);
    }

    public void begin(boolean isMock) {
        stopAnimations();
        selected.clear();
        acceptedAnalyses.clear();
        events.clear();
        nodes.clear();
        graph.nodeLayer.getChildren().clear();
        graph.linkLayer.getChildren().clear();
        routeFrom = null;
        routeTo = null;
        activeId = null;
        pinned = false;
        mock = isMock;
        saved = false;
        followCurrent.setText("현재 작업");
        running = true;
        ensureNode("coordinator").state(isMock ? "템플릿 테스트" : "호출 계획 중");
        if (!isMock) activeId = "coordinator";
        current.setText(isMock ? "Mock · 고정 템플릿을 확인하고 있습니다" : "코디네이터가 연구팀과 다음 호출을 계획하고 있습니다");
        provenance.setText(isMock ? "Mock 템플릿 테스트 · 실제 에이전트 호출·토론은 수행하지 않습니다."
                : "실제 호출과 공개 답변 · 노드를 눌러 방법과 기록 전문을 살펴보세요.");
        updateCount();
        inspect("coordinator", false);
        refreshNodes();
    }

    public void selectLenses(List<String> ids) {
        selected.clear();
        for (String id : ids) {
            if (catalog.containsKey(id)) {
                selected.add(id);
                ensureNode(id).state(mock ? "템플릿 선택" : saved ? "저장된 선택" : "배정됨");
            }
        }
        nodes.entrySet().removeIf(entry -> {
            if (!catalog.containsKey(entry.getKey()) || selected.contains(entry.getKey())) return false;
            graph.nodeLayer.getChildren().remove(entry.getValue().button);
            return true;
        });
        updateCount();
        refreshNodes();
        graph.requestLayout();
        refreshDetail();
    }

    public void stage(String message) {
        current.setText(message);
        if (running && !mock && message.contains("코디네이터") && message.contains("계획 중")) {
            settleActive();
            activeId = "coordinator";
            ensureNode(activeId).state("호출 계획 중");
            clearRoute();
            refreshNodes();
            if (!pinned) inspect(activeId, false);
        }
    }

    public void add(AgentEvent event) {
        if (events.stream().anyMatch(existing -> existing.equals(event))) return;
        events.add(event);
        settleActive();
        String actor = canonical(event.actor());
        String recipient = canonical(event.recipient());
        if (catalog.containsKey(actor) && selected.contains(actor)) ensureNode(actor);
        if (catalog.containsKey(recipient) && selected.contains(recipient)) ensureNode(recipient);
        if (isSpecial(actor)) ensureNode(actor);
        if (isSpecial(recipient)) ensureNode(recipient);
        String action = event.action();
        if ("analysis".equals(action) && selected.contains(actor) && "coordinator".equals(recipient)) {
            acceptedAnalyses.add(actor);
            nodes.get(actor).state("분석 완료");
        } else if ("analyze".equals(action) && selected.contains(recipient)) {
            activate(recipient, "독립 분석 중");
        } else if ("challenge".equals(action)) {
            state(actor, "반론 전달");
            activate(recipient, "답변 작성 중");
        } else if (List.of("reply", "response", "respond").contains(action)) {
            state(actor, "답변 완료");
            state(recipient, "반론 전달");
        } else if ("debate".equals(action)) {
            activate(selected.contains(recipient) ? recipient : "coordinator", "비평 요청 중");
        } else if ("red_team".equals(action)) {
            activate("red_team", "위험 검토 중");
        } else if ("review".equals(action)) {
            state("red_team", "검토 완료");
        } else if ("experiment".equals(action)) {
            if ("coordinator".equals(actor)) activate("experiment", "설계 중");
            else state("experiment", "설계 완료");
        } else if (List.of("synthesize", "synthesis", "conclude").contains(action)) {
            activate("coordinator", "결과 종합 중");
        } else if ("final_decision".equals(action)) {
            state("coordinator", "종합 완료");
            state("user", "결과 수신");
        } else if ("delegate".equals(action)) {
            activate("coordinator", "연구팀 구성 중");
        } else if ("rejected".equals(action)) {
            activate("coordinator", "호출 재계획 중");
        }
        routeFrom = graphId(actor);
        routeTo = graphId(recipient);
        if (!replaying) current.setText(Reporting.agentLabel(event.actor()) + " → "
                + Reporting.agentLabel(event.recipient()) + " · " + Reporting.actionLabel(action));
        updateCount();
        refreshNodes();
        graph.requestLayout();
        if (!pinned) inspectedId = activeId != null ? activeId
                : nodes.containsKey(actor) ? actor : "coordinator";
        refreshDetail();
        if (!replaying) {
            graph.layout();
            playTransfer();
        }
    }

    public void finish(String message) {
        String pending = activeId;
        running = false;
        settleActive();
        if (pending != null && !message.startsWith("분석 완료") && !message.startsWith("완료")) {
            state(pending, "작업 종료");
        }
        stopAnimations();
        current.setText(message);
        refreshNodes();
        refreshDetail();
    }

    public void replay(HarnessResult result) {
        begin(result.runtime().toLowerCase(java.util.Locale.ROOT).contains("mock"));
        running = false;
        saved = true;
        followCurrent.setText("종합 기록");
        replaying = true;
        activeId = null;
        selectLenses(result.selectedLenses());
        for (AgentEvent event : result.agentEvents()) add(event);
        replaying = false;
        settleActive();
        stopAnimations();
        current.setText("저장된 연구 기록 · 현재 실행 중인 분석이 아닙니다");
        provenance.setText("저장된 공개 기록 · " + (events.isEmpty() ? "이 보고서에는 공개 호출 기록이 없습니다."
                : "노드를 눌러 당시의 공개 지시와 답변 전문을 읽으세요."));
        if (events.isEmpty()) nodes.get("coordinator").state(mock ? "Mock 저장 기록" : "저장된 기록");
        pinned = false;
        inspect("coordinator", false);
        refreshNodes();
    }

    public void setReducedMotion(boolean reduced) {
        reducedMotion = reduced;
        updateMotion();
    }

    public void stop() {
        String pending = activeId;
        running = false;
        settleActive();
        if (pending != null) state(pending, "작업 종료");
        stopAnimations();
        refreshNodes();
    }

    private AgentNode ensureNode(String id) {
        return nodes.computeIfAbsent(id, key -> {
            AgentNode node = new AgentNode(key);
            graph.nodeLayer.getChildren().add(node.button);
            return node;
        });
    }

    private void activate(String id, String status) {
        if (mock || (!selected.contains(id) && catalog.containsKey(id))) return;
        ensureNode(id).state(status);
        activeId = id;
    }

    private void state(String id, String status) {
        if (nodes.containsKey(id)) nodes.get(id).state(status);
    }

    private void settleActive() {
        if (activeId != null && nodes.containsKey(activeId)) {
            AgentNode node = nodes.get(activeId);
            node.state(acceptedAnalyses.contains(activeId) ? "분석 완료"
                    : "coordinator".equals(activeId) ? "대기" : selected.contains(activeId) ? "배정됨" : "대기");
        }
        activeId = null;
    }

    private void inspect(String id, boolean pin) {
        inspectedId = nodes.containsKey(id) ? id : "coordinator";
        pinned = pin;
        refreshNodes();
        refreshDetail();
        detailScroll.setVvalue(0);
    }

    private void refreshNodes() {
        boolean dense = selected.size() > 9;
        for (AgentNode node : nodes.values()) {
            node.button.pseudoClassStateChanged(ACTIVE, running && node.id.equals(activeId));
            node.button.pseudoClassStateChanged(COMPLETE, acceptedAnalyses.contains(node.id));
            node.button.pseudoClassStateChanged(INSPECTED, node.id.equals(inspectedId));
            node.button.pseudoClassStateChanged(DENSE, dense && catalog.containsKey(node.id));
            node.button.setAccessibleText(title(node.id) + ", " + node.status.getText() + ", 방법과 공개 기록 보기");
        }
        graph.empty.setVisible(selected.isEmpty());
        updateMotion();
    }

    private void refreshDetail() {
        AgentNode node = nodes.get(inspectedId);
        if (node == null) {
            inspectedId = "coordinator";
            node = nodes.get(inspectedId);
        }
        detail.getChildren().setAll(label(pinned ? "선택한 노드" : "현재 작업 살펴보기", "council-eyebrow"),
                label(title(inspectedId), "council-detail-title"), label(node.status.getText(), "council-detail-state"));
        LensProfile profile = catalog.get(inspectedId);
        if (profile != null) {
            Label method = label(profile.thinkingStyle(), "council-method");
            VBox role = new VBox(8, label("맡은 방법론", "council-field-label"),
                    label(profile.responsibility(), "council-copy"), label("적용 지침", "council-field-label"),
                    label(profile.promptGuidance(), "council-copy"),
                    label("분야 · " + String.join(" · ", profile.routingTags()), "council-method"));
            TitledPane profilePane = new TitledPane("렌즈 역할과 적용 지침", role);
            profilePane.setExpanded(false);
            profilePane.setAnimated(false);
            profilePane.getStyleClass().add("council-profile");
            detail.getChildren().addAll(method, profilePane);
        } else {
            detail.getChildren().add(label(roleDescription(inspectedId), "council-method"));
        }
        List<AgentEvent> related = events.stream().filter(event ->
                canonical(event.actor()).equals(inspectedId) || canonical(event.recipient()).equals(inspectedId)
                || ("coordinator".equals(inspectedId) && "council".equals(canonical(event.recipient())))).toList();
        detail.getChildren().add(label("공개 기록 " + related.size(), "council-field-label"));
        if (related.isEmpty()) {
            detail.getChildren().add(label(mock ? "Mock 템플릿에는 실제 에이전트 호출 기록이 없습니다."
                    : saved ? "이 노드의 공개 호출 기록이 저장되지 않았습니다."
                    : "아직 이 노드의 공개 지시나 답변이 없습니다. 실제 기록이 도착하면 여기에 표시됩니다.", "council-copy"));
        }
        for (int i = related.size() - 1; i >= 0; i--) {
            AgentEvent event = related.get(i);
            VBox record = new VBox(8,
                    label(String.format(java.util.Locale.ROOT, "%02d", event.sequence()) + " · "
                            + Reporting.actionLabel(event.action()), "council-record-action"),
                    label(Reporting.agentLabel(event.actor()) + " → " + Reporting.agentLabel(event.recipient()), "council-method"),
                    label(event.content(), "council-copy"));
            record.getStyleClass().add("council-record");
            record.setId("council-record-" + event.sequence());
            record.setMinWidth(0);
            detail.getChildren().add(record);
        }
    }

    private void updateCount() {
        count.setText((selected.isEmpty() ? "선택 전" : "렌즈 " + selected.size() + " · 분석 수신 "
                + acceptedAnalyses.size() + "/" + selected.size()) + " · 공개 기록 " + events.size());
    }

    private void clearRoute() {
        routeFrom = null;
        routeTo = null;
        graph.route.setVisible(false);
        if (transfer != null) transfer.stop();
        graph.signal.setVisible(false);
    }

    private void playTransfer() {
        if (transfer != null) transfer.stop();
        graph.signal.setVisible(false);
        if (!canAnimate() || routeFrom == null || routeTo == null || routeFrom.equals(routeTo)) return;
        graph.updateRoute();
        Point2D from = graph.positions.get(routeFrom);
        if (from == null) return;
        graph.signal.setTranslateX(from.getX());
        graph.signal.setTranslateY(from.getY());
        graph.signal.setVisible(true);
        transfer = new PathTransition(ROUTE_TRANSFER, graph.route, graph.signal);
        transfer.setOnFinished(e -> graph.signal.setVisible(false));
        transfer.play();
    }

    private void updateMotion() {
        if (pulse != null) pulse.stop();
        nodes.values().forEach(node -> node.signal.setOpacity(1));
        if (!canAnimate()) {
            if (transfer != null) transfer.stop();
            graph.signal.setVisible(false);
            return;
        }
        if (activeId != null && nodes.containsKey(activeId)) {
            pulse = new FadeTransition(ACTIVE_PULSE, nodes.get(activeId).signal);
            pulse.setFromValue(1);
            pulse.setToValue(0.35);
            pulse.setAutoReverse(true);
            pulse.setCycleCount(Animation.INDEFINITE);
            pulse.play();
        }
    }

    private boolean canAnimate() {
        if (!running || mock || saved || replaying || reducedMotion || getScene() == null
                || getScene().getWindow() == null || !getScene().getWindow().isShowing()) return false;
        for (Node node = this; node != null; node = node.getParent()) if (!node.isVisible()) return false;
        return true;
    }

    private void stopAnimations() {
        if (pulse != null) pulse.stop();
        if (transfer != null) transfer.stop();
        nodes.values().forEach(node -> node.signal.setOpacity(1));
        graph.signal.setVisible(false);
    }

    private void observeWindow(Window window) {
        if (observedWindow != null) observedWindow.showingProperty().removeListener(visibilityListener);
        observedWindow = window;
        if (window != null) window.showingProperty().addListener(visibilityListener);
        updateMotion();
    }

    private void observeAncestors() {
        observedAncestors.forEach(parent -> parent.visibleProperty().removeListener(visibilityListener));
        observedAncestors.clear();
        for (Parent parent = getParent(); parent != null; parent = parent.getParent()) {
            observedAncestors.add(parent);
            parent.visibleProperty().addListener(visibilityListener);
        }
        updateMotion();
    }

    private String title(String id) {
        return switch (id) {
            case "coordinator" -> "코디네이터";
            case "red_team" -> "레드팀";
            case "experiment" -> "실험 설계";
            case "user" -> "연구 결과";
            default -> catalog.containsKey(id) ? catalog.get(id).displayName() : Reporting.agentLabel(id);
        };
    }

    private static String canonical(String id) {
        return switch (id) {
            case "orchestrator" -> "coordinator";
            case "red-team", "reviewer" -> "red_team";
            case "experiment_designer" -> "experiment";
            case "all", "agents", "lens_agents" -> "council";
            default -> id;
        };
    }

    private String graphId(String id) {
        return nodes.containsKey(id) ? id : "coordinator";
    }

    private static boolean isSpecial(String id) {
        return List.of("coordinator", "red_team", "experiment", "user").contains(id);
    }

    private static String roleDescription(String id) {
        return switch (id) {
            case "red_team" -> "증거의 빈틈, 실패 조건과 위험을 검토합니다.";
            case "experiment" -> "수집할 데이터와 성공·실패 기준을 갖춘 첫 실험을 설계합니다.";
            case "user" -> "가설, 가장 약한 가정과 다음 의사결정으로 종합한 공개 결과입니다.";
            default -> "질문에 맞는 방법론적 렌즈를 선택하고, 실제 분석·반론·답변을 요청한 뒤 결과를 종합합니다.";
        };
    }

    private static Label label(String text, String style) {
        Label label = new Label(text);
        label.getStyleClass().add(style);
        label.setWrapText(true);
        label.setMinWidth(0);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    private static Region spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    private final class AgentNode {
        final String id;
        final Button button = new Button();
        final Circle signal = new Circle(3);
        final Label status = label("대기", "council-node-state");
        final Label name;
        final VBox graphic;

        AgentNode(String id) {
            this.id = id;
            button.setId("council-node-" + id);
            button.getStyleClass().add("council-node");
            if ("coordinator".equals(id)) button.getStyleClass().add("council-coordinator");
            else if (catalog.containsKey(id)) {
                int category = new ArrayList<>(catalog.keySet()).indexOf(id) % 8 + 1;
                button.getStyleClass().add("council-category-" + category);
            } else button.getStyleClass().add("council-role");
            signal.getStyleClass().add("council-node-signal");
            name = label(title(id).replace(" Lens", ""), "council-node-name");
            name.setWrapText(false);
            HBox state = new HBox(5, signal, status);
            state.setAlignment(Pos.CENTER);
            graphic = new VBox(4, name, state);
            graphic.setAlignment(Pos.CENTER);
            graphic.setMouseTransparent(true);
            button.setGraphic(graphic);
            button.setOnAction(e -> inspect(id, true));
            button.setTooltip(new Tooltip(title(id) + " · 방법과 공개 기록 보기"));
            button.setManaged(false);
        }

        void state(String text) { status.setText(text); }
    }

    private final class Constellation extends Pane {
        final Pane dotLayer = new Pane();
        final Pane orbitLayer = new Pane();
        final Pane linkLayer = new Pane();
        final Pane nodeLayer = new Pane();
        final Path route = new Path();
        final Circle signal = new Circle(4);
        final Label empty = label("에이전트 구성 전\n질문에 맞는 렌즈를 선택합니다", "council-planning-note");
        final List<Ellipse> orbits = new ArrayList<>();
        final Map<String, Point2D> positions = new LinkedHashMap<>();
        double gridWidth = -1;
        double gridHeight = -1;

        Constellation() {
            getStyleClass().add("council-constellation");
            dotLayer.setMouseTransparent(true);
            orbitLayer.setMouseTransparent(true);
            linkLayer.setMouseTransparent(true);
            route.setMouseTransparent(true);
            signal.setMouseTransparent(true);
            route.getStyleClass().add("council-route");
            signal.getStyleClass().add("council-transfer");
            signal.setVisible(false);
            empty.setMouseTransparent(true);
            empty.setAlignment(Pos.CENTER);
            for (int i = 0; i < 3; i++) {
                Ellipse orbit = new Ellipse();
                orbit.getStyleClass().add("council-orbit");
                orbits.add(orbit);
                orbitLayer.getChildren().add(orbit);
            }
            getChildren().addAll(dotLayer, orbitLayer, linkLayer, route, signal, nodeLayer, empty);
        }

        @Override protected void layoutChildren() {
            double width = getWidth();
            double height = getHeight();
            boolean dense = selected.size() > 9;
            boolean compact = dense && height < 330;
            boolean hasRoles = nodes.size() > selected.size() + 1;
            double centerX = width / 2;
            double centerY = (height - (hasRoles ? 44 : 0)) / 2;
            double radiusX = Math.max(100, (width - (compact ? 96 : dense ? 100 : 144)) / 2);
            double radiusY = compact ? (height - (hasRoles ? 44 : 0) - 56) / 2
                    : Math.min(centerY - 28, Math.max(dense ? 116 : 106, Math.min(196, (height - 100) / 2)));
            positions.clear();
            positions.put("coordinator", new Point2D(centerX, centerY));
            int index = 0;
            int outerCount = dense ? Math.min(compact ? 11 : 8, selected.size()) : selected.size();
            for (String id : selected) {
                boolean inner = dense && index >= outerCount;
                int ringIndex = inner ? index - outerCount : index;
                int ringCount = inner ? selected.size() - outerCount : outerCount;
                double angle = -Math.PI / 2 + (inner ? compact ? 0.8 : Math.PI / ringCount : 0)
                        + Math.PI * 2 * ringIndex / Math.max(1, ringCount);
                double rx = radiusX * (inner ? 0.53 : 1);
                double ry = radiusY * (inner ? compact ? 0.45 : 0.56 : 1);
                positions.put(id, new Point2D(centerX + Math.cos(angle) * rx, centerY + Math.sin(angle) * ry));
                index++;
            }
            List<String> roles = nodes.keySet().stream().filter(id -> isSpecial(id) && !"coordinator".equals(id)).toList();
            for (int i = 0; i < roles.size(); i++) {
                positions.put(roles.get(i), new Point2D(centerX + (i - (roles.size() - 1) / 2.0) * 108, height - 21));
            }
            for (int i = 0; i < orbits.size(); i++) {
                Ellipse orbit = orbits.get(i);
                orbit.setCenterX(centerX);
                orbit.setCenterY(centerY);
                orbit.setRadiusX(radiusX * (i == 0 ? 1 : i == 1 ? 0.72 : 0.42));
                orbit.setRadiusY(radiusY * (i == 0 ? 1 : i == 1 ? 0.72 : 0.42));
            }
            if (gridWidth != width || gridHeight != height) {
                gridWidth = width;
                gridHeight = height;
                dotLayer.getChildren().clear();
                for (double x = 20; x < width; x += 24) {
                    for (double y = 16; y < height; y += 24) {
                        Circle dot = new Circle(x, y, 0.7);
                        dot.getStyleClass().add("council-grid-dot");
                        dotLayer.getChildren().add(dot);
                    }
                }
            }
            if (linkLayer.getChildren().size() != selected.size()) {
                linkLayer.getChildren().clear();
                for (int i = 0; i < selected.size(); i++) {
                    Line link = new Line();
                    link.getStyleClass().add("council-link");
                    linkLayer.getChildren().add(link);
                }
            }
            int linkIndex = 0;
            for (String id : selected) {
                Point2D position = positions.get(id);
                Line link = (Line) linkLayer.getChildren().get(linkIndex++);
                link.setStartX(centerX);
                link.setStartY(centerY);
                link.setEndX(position.getX());
                link.setEndY(position.getY());
            }
            nodes.forEach((id, node) -> {
                Point2D position = positions.getOrDefault(id, positions.get("coordinator"));
                boolean coordinator = "coordinator".equals(id);
                boolean role = !coordinator && isSpecial(id);
                double nodeWidth = coordinator ? compact ? 84 : 120 : role ? 100 : compact ? 76 : dense ? 88 : 124;
                double nodeHeight = coordinator ? compact ? 48 : 72 : role ? 34 : compact ? 36 : dense ? 44 : 60;
                node.button.pseudoClassStateChanged(COMPACT, compact);
                node.graphic.setSpacing(compact ? 0 : 4);
                node.button.resizeRelocate(position.getX() - nodeWidth / 2, position.getY() - nodeHeight / 2,
                        nodeWidth, nodeHeight);
            });
            empty.resizeRelocate(Math.max(8, centerX - 150), centerY + 48, 300, 52);
            updateRoute();
        }

        void updateRoute() {
            Point2D from = positions.get(routeFrom);
            Point2D to = positions.get(routeTo);
            route.setVisible(from != null && to != null && !routeFrom.equals(routeTo));
            if (!route.isVisible()) return;
            double dx = to.getX() - from.getX();
            double dy = to.getY() - from.getY();
            double bend = Math.min(32, Math.hypot(dx, dy) * 0.12);
            double length = Math.max(1, Math.hypot(dx, dy));
            route.getElements().setAll(new MoveTo(from.getX(), from.getY()),
                    new QuadCurveTo((from.getX() + to.getX()) / 2 - dy / length * bend,
                            (from.getY() + to.getY()) / 2 + dx / length * bend, to.getX(), to.getY()));
        }
    }
}
