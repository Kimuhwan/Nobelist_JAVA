package com.scientificlens.harness.core;

import com.scientificlens.harness.domain.HarnessResult;
import com.scientificlens.harness.domain.LensOutput;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Markdown and JSON renderers (same section layout as the Python reporting module). */
public final class Reporting {
    private Reporting() {}

    public static String markdown(HarnessResult r) {
        String lensCritiques = r.lensOutputs().stream().map(Reporting::lensSection).collect(Collectors.joining("\n\n---\n\n"));
        List<String> debates = new ArrayList<>();
        for (int i = 0; i < r.debateFindings().size(); i++) {
            var finding = r.debateFindings().get(i);
            String entry = "### " + (i + 1) + ". " + finding.sourceLensId() + " → " + finding.targetLensId()
                    + "\n\n" + finding.concern() + "\n\n**수정 제안:** " + finding.suggestedRevision();
            if (!r.lensRevisions().isEmpty()) {
                var revision = r.lensRevisions().get(i);
                entry += "\n\n**실제 답변:**\n\n" + revision.response();
                entry += revision.changedFields().isEmpty() ? "\n\n분석 값 유지 · 변경된 필드 없음"
                        : "\n\n**실제 수정 전·후 · " + revision.changedFields().size() + "개 항목 변경**";
                for (String field : revision.changedFields()) {
                    entry += "\n\n#### " + revisionFieldLabel(field) + "\n\n**수정 전:**\n\n"
                            + lensFieldValue(revision.before(), field) + "\n\n**수정 후:**\n\n"
                            + lensFieldValue(revision.after(), field);
                }
            } else {
                entry += "\n\n이 보고서 형식에는 실제 수정 전·후 분석 값이 저장되지 않았습니다.";
            }
            debates.add(entry);
        }
        String debate = String.join("\n\n", debates);
        String redTeam = r.redTeamFindings().stream()
                .map(f -> "#### [" + f.severity().value().toUpperCase() + "] " + f.issue() + "\n"
                        + "- **완화책:** " + f.mitigation())
                .collect(Collectors.joining("\n\n"));
        var plan = r.experimentPlan();
        var d = r.finalDecision();
        String report = String.join(
                "\n",
                "# Scientific Lens Harness Report",
                "",
                "> **질문:** " + r.problem().question(),
                "> **모드:** " + r.problem().mode().value()
                        + " · **문제 유형:** " + r.problemType().value()
                        + " · **렌즈:** " + String.join(", ", r.selectedLenses()),
                "",
                "---",
                "",
                "## 핵심 결론",
                "",
                "**가설:** " + d.coreHypothesis(),
                "",
                "**가장 약한 가정:** " + d.weakestAssumption(),
                "",
                "**시장 돌파구:** " + d.marketEntryWedge(),
                "",
                "---",
                "",
                "## 렌즈별 분석",
                "",
                lensCritiques,
                "",
                "---",
                "",
                "## 렌즈 간 토론",
                "",
                debate,
                "",
                "---",
                "",
                "## 레드팀 위험 평가",
                "",
                redTeam,
                "",
                "---",
                "",
                "## 첫 번째 실험 설계",
                "",
                "| 항목 | 내용 |",
                "|------|------|",
                "| 가설 | " + plan.hypothesis() + " |",
                "| 지표 | " + plan.metric() + " |",
                "| 대조군 | " + nullToDash(plan.controlGroup()) + " |",
                "| 처치군 | " + nullToDash(plan.treatmentGroup()) + " |",
                "| 기간 | " + plan.duration() + " |",
                "| 성공 기준 | " + plan.successCriteria() + " |",
                "| 실패 기준 | " + plan.failureCriteria() + " |",
                "| 위험 요소 | " + String.join(", ", plan.risks()) + " |",
                "",
                "---",
                "",
                "## 최종 의사결정 계약서",
                "",
                "| 항목 | 내용 |",
                "|------|------|",
                "| 핵심 가설 | " + d.coreHypothesis() + " |",
                "| 가장 약한 가정 | " + d.weakestAssumption() + " |",
                "| 필요 데이터 | " + String.join(", ", d.requiredData()) + " |",
                "| 첫 실험 | " + d.firstExperiment() + " |",
                "| 성공 기준 | " + d.successCriteria() + " |",
                "| 실패 기준 | " + d.failureCriteria() + " |",
                "| 다음 의사결정 | " + d.nextDecision() + " |",
                "| 시장 돌파구 | " + d.marketEntryWedge() + " |",
                "",
                "---",
                "*과학자 이름은 방법론 렌즈일 뿐이며, 실제 인물의 생각을 재현하지 않습니다. "
                        + "이 보고서는 증명이 아니라 검증 계획입니다.*");
        if (!r.agentEvents().isEmpty()) {
            report += "\n\n---\n\n## 에이전트 호출·토론 기록\n\n"
                    + "오케스트레이터의 공개 작업 지시와 실제 에이전트 답변입니다. 내부 추론 과정은 포함하지 않습니다.\n\n"
                    + r.agentEvents().stream().map(event -> "### " + event.sequence() + ". "
                            + agentLabel(event.actor()) + " → " + agentLabel(event.recipient())
                            + " · " + actionLabel(event.action()) + "\n\n" + event.content())
                            .collect(Collectors.joining("\n\n"));
        }
        return report;
    }

    public static String agentLabel(String id) {
        return switch (id) {
            case "orchestrator", "coordinator" -> "오케스트레이터";
            case "red_team", "red-team", "reviewer" -> "레드팀";
            case "experiment", "experiment_designer" -> "실험 설계";
            case "all", "council", "agents", "lens_agents" -> "연구 에이전트";
            case "host" -> "실행 제어";
            case "user" -> "사용자";
            default -> LensRegistry.load().stream().filter(lens -> lens.lensId().equals(id))
                    .findFirst().map(lens -> lens.displayName()).orElse(id);
        };
    }

    public static String actionLabel(String action) {
        return switch (action) {
            case "delegate" -> "에이전트 구성·작업 배정";
            case "analysis", "analyze", "analyze_agent" -> "독립 분석";
            case "debate" -> "토론 요청";
            case "challenge" -> "반론";
            case "reply", "response", "respond" -> "답변·가설 수정";
            case "red_team", "review" -> "레드팀 검토";
            case "experiment" -> "실험 설계";
            case "synthesize", "conclude", "synthesis", "final_decision" -> "최종 종합";
            case "rejected" -> "작업 검증·재요청";
            default -> action;
        };
    }
    public static String json(HarnessResult result) {
        return Json.write(result);
    }

    public static String revisionFieldLabel(String field) {
        return switch (field) {
            case "core_view" -> "핵심 관점";
            case "hypotheses" -> "가설";
            case "assumptions" -> "가정";
            case "evidence_needed" -> "필요한 증거";
            case "counterarguments" -> "반론";
            case "experiment_suggestions" -> "실험 제안";
            case "confidence" -> "확신도 · 모델 자체 평가";
            default -> throw new IllegalArgumentException("Unknown lens field: " + field);
        };
    }

    public static String lensFieldValue(LensOutput output, String field) {
        return switch (field) {
            case "core_view" -> output.coreView();
            case "hypotheses" -> lines(output.hypotheses());
            case "assumptions" -> lines(output.assumptions());
            case "evidence_needed" -> lines(output.evidenceNeeded());
            case "counterarguments" -> lines(output.counterarguments());
            case "experiment_suggestions" -> lines(output.experimentSuggestions());
            case "confidence" -> Double.toString(output.confidence());
            default -> throw new IllegalArgumentException("Unknown lens field: " + field);
        };
    }

    private static String lines(List<String> items) {
        return items.stream().map(value -> "• " + value).collect(Collectors.joining("\n"));
    }

    private static String lensSection(LensOutput o) {
        String hypotheses = o.hypotheses().stream()
                .map(h -> "  - " + h).collect(Collectors.joining("\n"));
        String assumptions = o.assumptions().stream()
                .map(a -> "  - " + a).collect(Collectors.joining("\n"));
        String evidence = o.evidenceNeeded().stream()
                .map(e -> "  - " + e).collect(Collectors.joining("\n"));
        String counterargs = o.counterarguments().stream()
                .map(c -> "  - " + c).collect(Collectors.joining("\n"));
        String experiments = o.experimentSuggestions().stream()
                .map(e -> "  - " + e).collect(Collectors.joining("\n"));
        return String.join(
                "\n",
                "### " + o.lensId() + String.format("  _(확신도 %.0f%%)_", o.confidence() * 100),
                "",
                "**관점:** " + o.coreView(),
                "",
                "**가설**",
                hypotheses,
                "",
                "**가정** _(가장 약한 것 먼저)_",
                assumptions,
                "",
                "**필요한 증거**",
                evidence,
                "",
                "**반론 / 예상치 못한 리스크**",
                counterargs,
                "",
                "**실험 제안** _(구체적 세그먼트 포함)_",
                experiments);
    }

    private static String nullToDash(String value) {
        return value == null ? "-" : value;
    }
}
