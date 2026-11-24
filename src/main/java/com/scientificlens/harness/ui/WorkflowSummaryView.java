package com.scientificlens.harness.ui;

import com.scientificlens.harness.domain.ExperimentPlan;
import com.scientificlens.harness.domain.FinalDecision;
import com.scientificlens.harness.domain.HarnessResult;
import com.scientificlens.harness.domain.RedTeamFinding;
import com.scientificlens.harness.domain.Severity;
import java.util.Comparator;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/** A reading-first report, composed from the complete analysis decision. */
final class WorkflowSummaryView extends VBox {
    WorkflowSummaryView(HarnessResult result) {
        super(24);
        getStyleClass().add("workbench-summary");
        setMinWidth(0);
        setMaxWidth(1180);
        FinalDecision decision = result.finalDecision();
        ExperimentPlan plan = result.experimentPlan();

        VBox masthead = new VBox(12,
                copy("SCIENTIFIC LENS HARNESS  /  분석 보고서", "report-eyebrow"),
                copy(result.problem().question().replaceAll("\\R+", " ").strip(), "report-question"));
        masthead.getStyleClass().add("report-masthead");
        FlowPane metadata = new FlowPane(12, 8,
                WorkbenchUi.badge("검증 전 · 실험 제안", "proposal-badge"),
                copy("모드 · " + result.problem().mode().label(), "workbench-helper"),
                copy("렌즈 · " + result.selectedLenses().size() + "개", "workbench-helper"));
        if (!result.agentEvents().isEmpty()) {
            metadata.getChildren().add(WorkbenchUi.badge("LLM 오케스트레이션 · 상호 토론 "
                    + result.debateFindings().size() + "회", "proposal-badge"));
        }
        masthead.getChildren().add(metadata);

        VBox nextDecision = WorkbenchUi.reportField("실험 후 내릴 결정",
                copy(decision.nextDecision(), "evaluation-value"), "decision-block");
        VBox hypothesis = WorkbenchUi.reportSection("01", "핵심 가설",
                "이 가설은 아직 검증되지 않았습니다. 아래 계획으로 필요한 증거를 수집합니다.",
                copy(decision.coreHypothesis(), "workbench-hypothesis"), nextDecision);
        hypothesis.getStyleClass().add("report-hero");

        VBox experiment = WorkbenchUi.reportSection("02", "첫 실험", "먼저 실행할 검증과 실험 설계",
                copy(decision.firstExperiment(), "workbench-copy"),
                WorkbenchUi.facts(
                        "실험 기간", plan.duration(),
                        "주요 지표", plan.metric(),
                        "대조군", plan.controlGroup() == null ? "대조군 없음" : plan.controlGroup(),
                        "처치군", plan.treatmentGroup() == null ? "처치군 없음" : plan.treatmentGroup()));

        VBox success = outcome("성공 기준", decision.successCriteria(), "icon-success", "outcome-success");
        VBox failure = outcome("실패 기준", decision.failureCriteria(), "icon-danger", "outcome-failure");
        VBox judgment = WorkbenchUi.reportSection("03", "판단을 위한 증거", "성공과 실패 기준은 실험 전에 정합니다.",
                WorkbenchUi.reportField("필요한 데이터", ResultView.bullets(decision.requiredData())),
                WorkbenchUi.readingColumns(success, failure));

        VBox assumption = WorkbenchUi.reportField("가장 약한 가정",
                copy(decision.weakestAssumption(), "quote-value"), "quote-box");
        VBox riskList = new VBox(20);
        riskList.setMinWidth(0);
        var sortedRisks = result.redTeamFindings().stream()
                .sorted(Comparator.comparing(RedTeamFinding::severity).reversed()).toList();
        for (int i = 0; i < sortedRisks.size(); i++) {
            RedTeamFinding risk = sortedRisks.get(i);
            Label severity = WorkbenchUi.badge(severityLabel(risk.severity()),
                    "risk-badge", "risk-" + risk.severity().value());
            HBox heading = new HBox(12, copy("레드팀 " + (i + 1), "field-title"), severity);
            heading.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            VBox entry = new VBox(12, heading, copy(risk.issue(), "workbench-copy"),
                    WorkbenchUi.reportField("완화책", copy(risk.mitigation(), "workbench-copy")));
            entry.getStyleClass().add("risk-entry");
            entry.setMinWidth(0);
            riskList.getChildren().add(entry);
            if (i < sortedRisks.size() - 1) {
                riskList.getChildren().add(WorkbenchUi.divider());
            }
        }
        VBox risks = WorkbenchUi.reportSection("04", "가정과 반론", "결론을 흔들 수 있는 조건부터 확인합니다.",
                assumption, riskList);

        VBox provenance = new VBox(16,
                WorkbenchUi.facts("질문 유형", result.problemType().value(),
                        "분석 엔진", result.runtime(),
                        "선택한 렌즈", String.join(" · ", result.selectedLenses())));
        if (result.problem().domainHint() != null) {
            provenance.getChildren().add(WorkbenchUi.reportField("도메인",
                    copy(result.problem().domainHint(), "workbench-copy")));
        }
        VBox strategy = WorkbenchUi.reportSection("05", "시장 진입 전략", null,
                copy(decision.marketEntryWedge(), "workbench-copy"),
                WorkbenchUi.disclosure("분석 정보 · 출처", provenance));

        getChildren().setAll(masthead, hypothesis, experiment, judgment, risks, strategy,
                copy("과학자 이름은 방법론 렌즈이며 실제 인물의 생각을 재현하지 않습니다. 결과는 증명이 아니라 검증 계획입니다.",
                        "disclaimer"));
    }

    private static VBox outcome(String title, String value, String iconStyle, String surfaceStyle) {
        Label heading = copy(title, "evaluation-title");
        heading.setGraphic(WorkbenchUi.icon("mdi2c-clipboard-check-outline", "icon-small", iconStyle));
        heading.setGraphicTextGap(8);
        VBox block = new VBox(12, heading, copy("사전 정의 · 관측 결과 아님", "evaluation-note"),
                copy(value, "evaluation-value"));
        block.getStyleClass().addAll("evaluation-item", surfaceStyle);
        block.setMinWidth(0);
        block.setMaxWidth(Double.MAX_VALUE);
        return block;
    }

    private static Label copy(String value, String style) {
        return ResultView.text(value, style);
    }

    private static String severityLabel(Severity severity) {
        return switch (severity) {
            case HIGH -> "높은 위험";
            case MEDIUM -> "주의 필요";
            case LOW -> "낮은 위험";
        };
    }
}
