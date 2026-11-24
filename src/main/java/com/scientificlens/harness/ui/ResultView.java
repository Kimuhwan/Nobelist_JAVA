package com.scientificlens.harness.ui;

import com.scientificlens.harness.core.LensRegistry;
import com.scientificlens.harness.core.Reporting;
import com.scientificlens.harness.domain.DebateFinding;
import com.scientificlens.harness.domain.ExperimentPlan;
import com.scientificlens.harness.domain.HarnessResult;
import com.scientificlens.harness.domain.LensOutput;
import com.scientificlens.harness.domain.LensProfile;
import com.scientificlens.harness.domain.RedTeamFinding;
import java.util.Comparator;
import java.util.List;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/** Renders the result as native, vertically growing report pages. */
final class ResultView {
    private static final String DISCLAIMER =
            "과학자 이름은 방법론 렌즈일 뿐이며 실제 인물의 생각을 재현하지 않습니다. 이 결과는 증명이 아니라 검증 계획입니다.";

    private final VBox summary = page();
    private final VBox lenses = page();
    private final VBox critique = page();
    private final VBox experiment = page();
    private final TextArea markdown = rawArea("code-area");
    private final TextArea json = rawArea("code-area");
    private final DebateGraphView debateGraph = new DebateGraphView();

    ResultView() {
        clear();
    }

    Node summaryPage() { return scroll(summary); }
    Node lensPage() { return scroll(lenses); }
    Node critiquePage() { return scroll(critique); }
    Node experimentPage() { return scroll(experiment); }
    TextArea markdownArea() { return markdown; }
    TextArea jsonArea() { return json; }

    void clear() {
        summary.getChildren().setAll(emptyState("아직 분석 결과가 없습니다",
                "상단에 질문을 입력하고 [분석 실행]을 누르세요. 핵심 가설과 첫 실험이 여기에 정리됩니다."));
        lenses.getChildren().setAll(emptyState("렌즈 분석 대기 중", "선택된 렌즈별 독립 분석이 여기에 표시됩니다."));
        critique.getChildren().setAll(emptyState("토론·레드팀 대기 중", "렌즈 간 반박과 레드팀 위험 평가가 여기에 표시됩니다."));
        experiment.getChildren().setAll(emptyState("실험 계획 대기 중", "대조군, 지표, 성공/실패 기준이 여기에 표시됩니다."));
        markdown.clear();
        json.clear();
        debateGraph.clear();
    }

    void loading() {
        clear();
        summary.getChildren().setAll(emptyState("아이디어를 분석하고 있습니다",
                "상단 단계와 하단 진행률에서 상태를 확인할 수 있습니다. 실시간 로그에서 생성 중인 출력을 확인하세요."));
    }

    void stopped(String message) {
        summary.getChildren().setAll(emptyState("분석이 중단되었습니다",
                message + " · 질문과 엔진 설정을 확인한 뒤 다시 실행하세요. 상세 원인은 실시간 로그에서 확인할 수 있습니다."));
    }

    void show(HarnessResult result) {
        summary.getChildren().setAll(new WorkflowSummaryView(result));
        renderLenses(result);
        renderCritique(result);
        renderExperiment(result.experimentPlan());
        String question = result.problem().question();
        String inlineQuestion = question.replaceAll("\\R+", " ").strip();
        if (!question.equals(inlineQuestion)) {
            for (Node page : List.of(summary, lenses, critique, experiment)) {
                normalizeQuotedQuestion(page, question, inlineQuestion);
            }
        }
        markdown.setText(Reporting.markdown(result));
        json.setText(Reporting.json(result));
    }

    private static void normalizeQuotedQuestion(Node node, String original, String inline) {
        if (node instanceof Label label) {
            label.setText(label.getText().replace(original, inline));
        }
        if (node instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> normalizeQuotedQuestion(child, original, inline));
        }
    }

    private void renderLenses(HarnessResult result) {
        VBox report = reportBody();
        report.getChildren().add(pageHeading("독립 분석", "선택된 방법론 렌즈",
                result.lensOutputs().size() + (result.agentEvents().isEmpty() ? "개 렌즈가 다른 렌즈의 결과를 보지 않고 분석했습니다. 확신도는 렌즈의 자체 평가입니다."
                        : "개 렌즈의 개별 분석과 실제 토론 후 수정한 결과입니다. 확신도는 렌즈의 자체 평가입니다.")));
        for (int i = 0; i < result.lensOutputs().size(); i++) {
            report.getChildren().add(lensCard(result.lensOutputs().get(i), i + 1));
        }
        report.getChildren().add(text(DISCLAIMER, "disclaimer"));
        lenses.getChildren().setAll(report);
    }

    private Node lensCard(LensOutput output, int index) {
        LensProfile profile = profile(output.lensId());
        VBox titles = new VBox(8,
                text(String.format("%02d", index) + "  /  " + (profile == null ? output.lensId() : profile.displayName()), "lens-name"),
                text(profile == null ? output.lensId() : profile.thinkingStyle(), "lens-style"));
        titles.setMinWidth(0);
        HBox.setHgrow(titles, Priority.ALWAYS);

        ProgressBar bar = new ProgressBar(output.confidence());
        bar.setPrefWidth(120);
        bar.setAccessibleText("렌즈 자체 확신도 " + output.confidence());
        VBox confidence = new VBox(8, text("자체 확신도 " + output.confidence(), "confidence-label"), bar);
        confidence.setAlignment(Pos.CENTER_RIGHT);
        confidence.setMinWidth(Region.USE_PREF_SIZE);
        HBox header = new HBox(24, titles, confidence);
        header.setAlignment(Pos.TOP_LEFT);
        header.setMinWidth(0);

        Node analysis = WorkbenchUi.readingColumns(
                section("가설", bullets(output.hypotheses())),
                section("가정 · 가장 약한 것 먼저", bullets(output.assumptions())),
                section("필요한 증거", bullets(output.evidenceNeeded())),
                section("반론", bullets(output.counterarguments())),
                section("실험 제안", bullets(output.experimentSuggestions())));
        VBox card = card("lens-report");
        card.getChildren().addAll(header, WorkbenchUi.divider(),
                section("핵심 관점", value(output.coreView())), analysis);
        if (profile != null) {
            VBox context = new VBox(20,
                    section("책임", value(profile.responsibility())),
                    section("라우팅 태그", bullets(profile.routingTags())),
                    section("분석 지침", value(profile.promptGuidance())));
            card.getChildren().add(WorkbenchUi.disclosure("렌즈 정보 · " + profile.lensId(), context));
        }
        return card;
    }

    private void renderCritique(HarnessResult result) {
        debateGraph.setFindings(result.debateFindings());
        debateGraph.setPrefHeight(380);
        debateGraph.setMaxWidth(Double.MAX_VALUE);
        VBox report = reportBody();
        report.getChildren().addAll(pageHeading("비판적 검토", "렌즈 간 토론과 레드팀",
                result.agentEvents().isEmpty() ? "가설의 빈틈과 잘못된 결론으로 이어질 수 있는 경로를 검토합니다."
                        : "오케스트레이터가 선택한 렌즈를 각각 호출해 반론과 답변을 주고받은 기록입니다."),
                WorkbenchUi.reportHeading("01", "렌즈 간 토론", null),
                text("노드 = 렌즈 · 화살표 = 비판 방향 (A → B: A가 B의 분석을 지적). 노드나 화살표에 마우스를 올리면 내용을 볼 수 있습니다.", "caption"),
                debateGraph);
        for (int i = 0; i < result.debateFindings().size(); i++) {
            DebateFinding finding = result.debateFindings().get(i);
            VBox revision = section("수정 제안", value(finding.suggestedRevision()));
            revision.getStyleClass().add("suggestion-box");
            VBox card = card();
            card.getChildren().addAll(text(String.format("%02d", i + 1) + "  /  "
                            + name(finding.sourceLensId()) + "  →  " + name(finding.targetLensId()), "debate-route"),
                    value(finding.concern()), revision);
            if (!result.lensRevisions().isEmpty()) {
                var accepted = result.lensRevisions().get(i);
                card.getChildren().add(section("실제 답변 · " + name(finding.targetLensId()), value(accepted.response())));
                if (accepted.changedFields().isEmpty()) {
                    card.getChildren().add(text("분석 값 유지 · 변경된 항목 없음", "caption"));
                } else {
                    VBox comparisons = new VBox(24);
                    comparisons.setMinWidth(0);
                    for (String field : accepted.changedFields()) {
                        comparisons.getChildren().add(new VBox(12,
                                text(Reporting.revisionFieldLabel(field), "field-title"),
                                WorkbenchUi.readingColumns(
                                        WorkbenchUi.reportField("수정 전", value(Reporting.lensFieldValue(accepted.before(), field)), "revision-before"),
                                        WorkbenchUi.reportField("수정 후", value(Reporting.lensFieldValue(accepted.after(), field)), "revision-after"))));
                    }
                    comparisons.getChildren().add(text("전체 수정 전·후 분석 값은 JSON의 lens_revisions에 보존됩니다.", "caption"));
                    var compare = WorkbenchUi.disclosure("실제 수정 전·후 · " + accepted.changedFields().size() + "개 항목 변경", comparisons);
                    compare.setId("revision-comparison-" + (i + 1));
                    card.getChildren().add(compare);
                }
            } else {
                card.getChildren().add(text("이 보고서 형식에는 실제 수정 전·후 값이 저장되지 않았습니다.", "caption"));
            }
            report.getChildren().add(card);
        }
        if (!result.agentEvents().isEmpty()) {
            VBox transcript = new VBox(24);
            transcript.setMinWidth(0);
            transcript.getChildren().add(text("공개 작업 지시와 실제 답변만 기록합니다. 내부 추론 과정은 포함하지 않습니다.", "workbench-helper"));
            for (var event : result.agentEvents()) {
                VBox entry = new VBox(12,
                        text(String.format("%02d", event.sequence()) + "  /  "
                                + Reporting.agentLabel(event.actor()) + " → " + Reporting.agentLabel(event.recipient()), "debate-route"),
                        text(Reporting.actionLabel(event.action()), "field-title"),
                        value(event.content()));
                entry.setMinWidth(0);
                transcript.getChildren().add(entry);
                transcript.getChildren().add(WorkbenchUi.divider());
            }
            var disclosure = WorkbenchUi.disclosure("에이전트 호출·토론 기록 · " + result.agentEvents().size() + "건", transcript);
            disclosure.setId("agent-transcript");
            report.getChildren().add(disclosure);
        }
        report.getChildren().add(WorkbenchUi.reportHeading("02", "레드팀",
                "위험의 심각도, 문제, 완화책을 함께 읽으세요."));
        List<RedTeamFinding> sorted = result.redTeamFindings().stream()
                .sorted(Comparator.comparing(RedTeamFinding::severity).reversed()).toList();
        for (RedTeamFinding finding : sorted) {
            Label badge = WorkbenchUi.badge(switch (finding.severity()) {
                case HIGH -> "위험 높음";
                case MEDIUM -> "위험 중간";
                case LOW -> "위험 낮음";
            }, "risk-badge", "risk-" + finding.severity().value());
            VBox card = card(switch (finding.severity()) {
                case HIGH -> "accent-danger";
                case MEDIUM -> "accent-warn";
                case LOW -> "accent-success";
            });
            card.getChildren().addAll(badge, value(finding.issue()), section("완화책", value(finding.mitigation())));
            report.getChildren().add(card);
        }
        report.getChildren().add(text(DISCLAIMER, "disclaimer"));
        critique.getChildren().setAll(report);
    }

    private void renderExperiment(ExperimentPlan plan) {
        VBox report = reportBody();
        report.getChildren().addAll(pageHeading("검증 설계", "실험 계획", "실행 전에 지표와 기준을 고정하세요. 아래 내용은 수행 결과가 아닌 제안입니다."),
                WorkbenchUi.reportSection("01", "검증할 가설", null, text(plan.hypothesis(), "core-value")),
                WorkbenchUi.reportSection("02", "실험 설계", null,
                        WorkbenchUi.facts("주요 지표", plan.metric(), "기간", plan.duration(),
                                "대조군", orDash(plan.controlGroup()), "처치군", orDash(plan.treatmentGroup()))),
                WorkbenchUi.reportSection("03", "측정과 판정", "실험 전에 정의하는 기준이며, 관측 결과가 아닙니다.",
                        section("필요 데이터", bullets(plan.requiredData())),
                        WorkbenchUi.readingColumns(
                                outcome("성공 기준", plan.successCriteria(), "outcome-success"),
                                outcome("실패 기준", plan.failureCriteria(), "outcome-failure"))),
                WorkbenchUi.reportSection("04", "결과를 무효화할 수 있는 위험", null, bullets(plan.risks())));
        if (plan.controlGroup() == null) {
            VBox warning = section("대조군 확인",
                    text("대조군이 없습니다. 인과 효과를 주장하기 전에 비교 가능한 기준 집단을 추가하는 것을 권장합니다.", "field-value"));
            warning.getStyleClass().add("quote-box");
            report.getChildren().add(warning);
        }
        report.getChildren().add(text(DISCLAIMER, "disclaimer"));
        experiment.getChildren().setAll(report);
    }

    private static VBox page() {
        VBox page = new VBox();
        page.getStyleClass().add("page");
        page.setAlignment(Pos.TOP_CENTER);
        page.setMinWidth(0);
        return page;
    }

    private static VBox reportBody() {
        VBox report = new VBox(24);
        report.setMinWidth(0);
        report.setMaxWidth(1180);
        return report;
    }

    private static VBox pageHeading(String eyebrow, String title, String description) {
        VBox heading = new VBox(12, text(eyebrow, "report-eyebrow"), text(title, "report-question"), text(description, "workbench-helper"));
        heading.getStyleClass().add("report-masthead");
        heading.setMinWidth(0);
        return heading;
    }

    private static ScrollPane scroll(VBox content) {
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("page-scroll");
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        return scroll;
    }

    private static TextArea rawArea(String style) {
        TextArea area = new TextArea();
        area.setEditable(false);
        area.setWrapText(true);
        area.getStyleClass().add(style);
        return area;
    }

    private static Node emptyState(String title, String sub) {
        Label mark = new Label();
        mark.setGraphic(WorkbenchUi.icon("mdi2f-flask-outline", "icon-primary"));
        mark.getStyleClass().add("empty-mark");
        Label heading = text(title, "empty-title");
        Label description = text(sub, "empty-sub");
        heading.setAlignment(Pos.CENTER);
        description.setAlignment(Pos.CENTER);
        description.setMaxWidth(420);
        description.getStyleClass().add("centered-copy");
        VBox box = new VBox(16, mark, heading, description);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(80, 24, 80, 24));
        return box;
    }

    static Label text(String text, String... styles) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMinWidth(0);
        label.setMinHeight(Region.USE_PREF_SIZE);
        label.setMaxWidth(Double.MAX_VALUE);
        label.getStyleClass().addAll(styles);
        return label;
    }

    private static Label value(String text) { return text(text, "field-value"); }

    private static VBox card(String... styles) {
        VBox card = new VBox(20);
        card.getStyleClass().add("card");
        card.getStyleClass().addAll(styles);
        card.setMinWidth(0);
        card.setMaxWidth(Double.MAX_VALUE);
        return card;
    }

    private static VBox section(String title, Node body) {
        return WorkbenchUi.reportField(title, body);
    }

    static VBox bullets(List<String> items) {
        VBox list = new VBox(12);
        list.setMinWidth(0);
        for (String item : items) {
            Label marker = text("•", "list-marker");
            marker.setMinWidth(Region.USE_PREF_SIZE);
            Label label = value(item);
            HBox row = new HBox(12, marker, label);
            row.setMinWidth(0);
            row.setAlignment(Pos.TOP_LEFT);
            HBox.setHgrow(label, Priority.ALWAYS);
            list.getChildren().add(row);
        }
        return list;
    }

    private static VBox outcome(String title, String criteria, String style) {
        VBox group = section(title, value(criteria));
        group.getStyleClass().addAll("evaluation-item", style);
        return group;
    }

    private static LensProfile profile(String lensId) {
        try { return LensRegistry.get(lensId); }
        catch (IllegalArgumentException e) { return null; }
    }

    private static String name(String lensId) {
        LensProfile profile = profile(lensId);
        return profile == null ? lensId : profile.displayName();
    }

    private static String orDash(String text) { return text == null ? "없음" : text; }
}
