package com.scientificlens.harness.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.scientificlens.harness.domain.DebateFinding;
import com.scientificlens.harness.domain.AgentEvent;
import com.scientificlens.harness.domain.ExperimentPlan;
import com.scientificlens.harness.domain.FinalDecision;
import com.scientificlens.harness.domain.HarnessResult;
import com.scientificlens.harness.domain.LensOutput;
import com.scientificlens.harness.domain.LensRevision;
import com.scientificlens.harness.domain.Mode;
import com.scientificlens.harness.domain.RedTeamFinding;
import com.scientificlens.harness.domain.Severity;
import com.scientificlens.harness.domain.UserProblem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;

class PdfReportWriterTest {
    private static final Path SAMPLES = Path.of("target", "report-qa");

    @Test
    void writesEveryResultFieldAsSearchableKoreanWithEmbeddedFontsAndBookmarks() throws IOException {
        HarnessResult result = normalResult();
        Files.createDirectories(SAMPLES);
        Path sample = SAMPLES.resolve("normal-report.pdf");
        PdfReportWriter.write(result, sample);
        try (PDDocument document = Loader.loadPDF(sample.toFile())) {
            String allText = new PDFTextStripper().getText(document);
            assertTrue(allText.contains("연구 검증 계획"));
            assertTrue(allText.contains("가설 미검증"));
            assertTrue(allText.contains("사전 선언"));
            assertTrue(allText.contains("실제 인물의 사적인 생각"));
            assertEquals(result.problem().question(), document.getDocumentInformation().getSubject());
            assertAllFields(result, new BodyTextStripper().getText(document));
            assertFontsAndPages(document);
            var bookmark = document.getDocumentCatalog().getDocumentOutline().getFirstChild();
            for (String title : List.of("핵심 가설과 다음 결정", "첫 번째 실험", "데이터와 사전 판단 기준",
                    "가장 약한 가정과 레드팀 비판", "시장 진입 전략", "전체 실험 설계", "선택된 렌즈별 분석", "렌즈 간 토론")) {
                assertNotNull(bookmark, title);
                assertTrue(bookmark.getTitle().endsWith(title));
                assertNotNull(bookmark.findDestinationPage(document));
                bookmark = bookmark.getNextSibling();
            }
            assertTrue(bookmark == null);
        }
    }

    @Test
    void preservesLongParagraphsListsUnbrokenCjkAndUnicodeFallbackThroughFinalPage() throws IOException {
        HarnessResult result = longResult();
        Files.createDirectories(SAMPLES);
        Path sample = SAMPLES.resolve("long-report.pdf");
        PdfReportWriter.write(result, sample);
        try (PDDocument document = Loader.loadPDF(sample.toFile())) {
            assertTrue(document.getNumberOfPages() >= 12, "long fixture must exercise page continuation");
            String body = new BodyTextStripper().getText(document);
            assertAllFields(result, body);
            assertTrue(body.contains("[U+10FFFF]"), "unsupported code point must remain visible");
            assertTrue(body.contains("[U+D800]"), "unpaired surrogate must remain visible");
            assertFalse(body.contains("\uFFFD"), "source must not be silently replaced");
            String allText = new PDFTextStripper().getText(document);
            assertTrue(allText.contains("/ 계속"));
            assertTrue(normalize(body).contains(normalize("모든 긴 항목의 마지막 결정 기록.")));
            PDFTextStripper last = new PDFTextStripper();
            last.setStartPage(document.getNumberOfPages());
            last.setEndPage(document.getNumberOfPages());
            assertTrue(last.getText(document).contains("보고서 읽기 안내"));
            assertFontsAndPages(document);
        }
    }

    private static void assertAllFields(HarnessResult result, String extracted) {
        List<String> values = new ArrayList<>();
        values.add(result.problem().question());
        values.add(result.problem().mode().value());
        values.add(result.problemType().value());
        values.add(result.runtime());
        if (result.problem().domainHint() != null) {
            values.add(result.problem().domainHint());
        }
        values.addAll(result.selectedLenses());
        var decision = result.finalDecision();
        values.addAll(List.of(decision.coreHypothesis(), decision.weakestAssumption(), decision.firstExperiment(),
                decision.successCriteria(), decision.failureCriteria(), decision.nextDecision(), decision.marketEntryWedge()));
        values.addAll(decision.requiredData());
        var plan = result.experimentPlan();
        values.addAll(List.of(plan.hypothesis(), plan.metric(), plan.duration(), plan.successCriteria(), plan.failureCriteria()));
        values.addAll(plan.requiredData());
        values.addAll(plan.risks());
        if (plan.controlGroup() != null) {
            values.add(plan.controlGroup());
        }
        if (plan.treatmentGroup() != null) {
            values.add(plan.treatmentGroup());
        }
        for (LensOutput output : result.lensOutputs()) {
            var profile = LensRegistry.get(output.lensId());
            values.addAll(List.of(profile.lensId(), profile.displayName(), profile.thinkingStyle(),
                    profile.responsibility(), profile.promptGuidance(), output.coreView(), Double.toString(output.confidence())));
            values.addAll(profile.routingTags());
            values.addAll(output.hypotheses());
            values.addAll(output.assumptions());
            values.addAll(output.evidenceNeeded());
            values.addAll(output.counterarguments());
            values.addAll(output.experimentSuggestions());
        }
        for (var finding : result.debateFindings()) {
            values.addAll(List.of(finding.sourceLensId(), finding.targetLensId(), finding.concern(), finding.suggestedRevision()));
        }
        for (var revision : result.lensRevisions()) {
            values.add(revision.response());
            for (String field : revision.changedFields()) {
                values.add(Reporting.lensFieldValue(revision.before(), field));
                values.add(Reporting.lensFieldValue(revision.after(), field));
            }
        }
        for (var finding : result.redTeamFindings()) {
            values.addAll(List.of(finding.issue(), finding.severity().value().toUpperCase(), finding.mitigation()));
        }
        for (var event : result.agentEvents()) {
            values.add(event.content());
        }
        String actual = normalize(extracted);
        for (String value : values) {
            String expected = normalize(value.replace("\uDBFF\uDFFF", "[U+10FFFF]").replace("\uD800", "[U+D800]"));
            assertTrue(actual.contains(expected), () -> "Missing result text: " + value);
        }
    }

    @Test
    void preservesFullPublicAgentTranscriptAndItsBookmark() throws IOException {
        HarnessResult base = normalResult();
        var events = List.of(new AgentEvent(1, "coordinator", "newton", "analyze", "독립 분석 작업을 배정한다."),
                new AgentEvent(2, "newton", "galileo", "challenge", "측정한 작동 시간과 안전 검수 시간을 구분해 비교해야 한다."),
                new AgentEvent(3, "galileo", "newton", "reply", "수정을 수용하고 두 시간을 분리해 기록한다.\n"
                        + "검수 실패자는 성공 시간에서 제외하되 실패율에 반드시 포함한다.\n".repeat(120)));
        var result = new HarnessResult(base.problem(), base.problemType(), base.runtime(), base.selectedLenses(),
                base.lensOutputs(), base.debateFindings(), base.redTeamFindings(), base.experimentPlan(), base.finalDecision(), events);
        Files.createDirectories(SAMPLES);
        Path sample = SAMPLES.resolve("transcript-report.pdf");
        PdfReportWriter.write(result, sample);
        try (var document = Loader.loadPDF(sample.toFile())) {
            assertAllFields(result, new BodyTextStripper().getText(document));
            assertFontsAndPages(document);
            var bookmark = document.getDocumentCatalog().getDocumentOutline().getFirstChild();
            int count = 0;
            while (bookmark != null) {
                count++;
                if (count == 9) {
                    assertTrue(bookmark.getTitle().endsWith("에이전트 호출·토론 기록"));
                    assertNotNull(bookmark.findDestinationPage(document));
                }
                bookmark = bookmark.getNextSibling();
            }
            assertEquals(9, count);
        }
    }

    @Test
    void exportsExactTypedRevisionResponseAndEveryChangedBeforeAfterValue() throws IOException {
        HarnessResult base = normalResult();
        LensOutput before = base.lensOutputs().get(1);
        LensOutput after = new LensOutput(before.lensId(), "수정 후 관점: 원자료와 행동 변경을 연결한다.",
                List.of("수정 후 가설: 사전 등록한 비교로 검증한다."), List.of("수정 후 가정: 누락 기록을 확인한다."),
                List.of("수정 후 증거: 날짜별 원자료"), List.of("수정 후 반론: 교란 요인"),
                List.of("수정 후 실험: 무작위 배정과 안전 중단"), 0.37);
        DebateFinding finding = new DebateFinding(base.selectedLenses().getFirst(), before.lensId(),
                "기존 분석이 기록 누락에 취약하다.", "누락 자료와 행동 변경을 함께 기록한다.");
        LensRevision revision = new LensRevision(finding, "반론을 수용하고 사전 등록 기준을 보완했습니다.\n답변 전문이 보존됩니다.", before, after);
        List<LensOutput> outputs = base.lensOutputs().stream().map(output -> output.lensId().equals(after.lensId()) ? after : output).toList();
        HarnessResult result = new HarnessResult(base.problem(), base.problemType(), base.runtime(), base.selectedLenses(),
                outputs, List.of(finding), base.redTeamFindings(), base.experimentPlan(), base.finalDecision(), List.of(), List.of(revision));
        assertEquals(7, revision.changedFields().size());
        Path sample = SAMPLES.resolve("revision-report.pdf");
        Files.createDirectories(SAMPLES);
        PdfReportWriter.write(result, sample);
        try (PDDocument document = Loader.loadPDF(sample.toFile())) {
            assertAllFields(result, new BodyTextStripper().getText(document));
            assertFontsAndPages(document);
        }
        String markdown = Reporting.markdown(result);
        assertTrue(markdown.contains(revision.response()));
        for (String field : revision.changedFields()) {
            assertTrue(markdown.contains(Reporting.lensFieldValue(before, field)));
            assertTrue(markdown.contains(Reporting.lensFieldValue(after, field)));
        }
    }

    private static void assertFontsAndPages(PDDocument document) throws IOException {
        Set<String> fonts = new HashSet<>();
        for (int index = 0; index < document.getNumberOfPages(); index++) {
            var page = document.getPage(index);
            assertEquals(PDRectangle.A4.getWidth(), page.getMediaBox().getWidth());
            assertEquals(PDRectangle.A4.getHeight(), page.getMediaBox().getHeight());
            for (var name : page.getResources().getFontNames()) {
                var font = page.getResources().getFont(name);
                assertTrue(font instanceof PDType0Font);
                assertTrue(font.isEmbedded());
                assertNotNull(font.getFontDescriptor().getFontFile2());
                assertTrue(font.getName().matches("[A-Z]{6}\\+.*"), "font must be a portable subset");
                fonts.add(font.getName());
            }
            assertFalse(page.getResources().getXObjectNames().iterator().hasNext(), "report text must not be a raster image");
            PDFTextStripper onePage = new PDFTextStripper();
            onePage.setStartPage(index + 1);
            onePage.setEndPage(index + 1);
            String text = onePage.getText(document);
            assertTrue(text.contains((index + 1) + " / " + document.getNumberOfPages()));
            assertTrue(text.contains("검증 계획 · 실험 결과 아님"));
        }
        assertEquals(2, fonts.size(), "regular and bold fonts must both be embedded");
    }

    private static HarnessResult normalResult() {
        HarnessResult mock = Orchestrator.analyzeMock(new UserProblem(
                "소규모 농산물 유통업체의 수요 예측에 AI를 적용하면 폐기량을 줄일 수 있을까?\n운영 부담과 비용까지 함께 검증하고 싶다.",
                Mode.FAST, "농산물 유통 · 소규모 운영 실험"));
        List<LensOutput> outputs = mock.lensOutputs().stream().map(output -> new LensOutput(output.lensId(),
                "예측 정확도와 실제 발주 행동을 구분해 인과 경로를 확인한다. " + output.coreView(),
                List.of("예측 정보가 발주 수량에 반영되면 특정 품목의 폐기 비율이 낮아질 수 있다.",
                        "설명 가능한 권고는 담당자의 반복 사용을 돕지만 운영 시간을 늘릴 수 있다."),
                List.of("과거 판매와 폐기 기록이 비교 가능한 수준으로 축적되어 있다.", "처치군과 대조군의 품목 구성이 비슷하다."),
                List.of("품목별 입고·판매·폐기 원자료", "권고 확인과 실제 발주 변경 이력"),
                List.of("기상과 가격 변화가 효과처럼 보일 수 있다.", "수요 예측 개선만으로 폐기를 줄일 수 없을 수 있다."),
                List.of("담당자와 품목을 정해 소규모 비교 실험을 시행한다.", "권고 수용 이유와 거절 이유를 함께 기록한다."), output.confidence())).toList();
        ExperimentPlan plan = new ExperimentPlan("발주 권고를 사용하는 품목군은 비교 품목군보다 폐기 비율이 낮아진다.",
                "입고량 대비 폐기량 비율과 담당자 하루 운영 시간", List.of("실험 전 품목별 폐기 비율", "매일 입고·판매·폐기량", "권고 노출과 발주 변경 이력"),
                "비슷한 판매 규모와 신선도를 가진 기존 방식 품목군", "AI 권고를 확인하고 발주에 반영하는 품목군", "기준선 2주, 비교 실험 4주",
                "사전에 정한 폐기 비율 감소 폭을 충족하고 담당자 운영 시간 증가가 허용 범위 이내이면 후속 검증을 진행한다.",
                "기준선 자료가 불충분하거나 폐기 비율이 개선되지 않거나 운영 부담이 허용 범위를 넘으면 중단하거나 설계를 수정한다.",
                List.of("계절성과 할인 행사로 인한 교란", "판매 기회 손실", "기록 누락과 측정 방식 차이"));
        FinalDecision decision = new FinalDecision("AI 수요 예측 권고를 발주에 반영하는 소규모 유통업체는 운영 부담을 통제하면서 농산물 폐기를 줄일 수 있다.",
                "예측 권고가 실제 발주 행동을 바꾸고, 담당자가 그 권고를 지속적으로 사용할 것이라는 가정이다.",
                List.of("품목별 기준선 판매·폐기 기록", "권고와 발주 변경 기록", "작업 시간과 서비스 운영 비용"),
                "먼저 업체 한 곳에서 비교 가능한 두 품목군을 선정한다. 기준선을 수집한 뒤 한 품목군에만 발주 권고를 제공하고 폐기 비율과 운영 시간을 함께 측정한다.",
                "실험 전에 합의한 폐기 비율 감소 기준을 충족하며 추가 운영 시간이 허용 범위를 넘지 않는다.",
                "개선이 관찰되지 않거나 비교 자료의 품질이 낮거나 운영 부담과 판매 손실이 이익을 상쇄한다.",
                "성공 기준을 충족하면 다른 업체에서 재현성을 확인한다. 실패 기준을 충족하면 원인을 구분해 권고 방식과 데이터 수집을 수정하거나 중단한다.",
                "고가의 통합 시스템을 도입하기 어려운 소규모 유통업체의 한 품목군에서 시작한다. 실제 기록과 운영 부담을 확인한 후 적용 범위를 넓힐지 판단한다.");
        return new HarnessResult(mock.problem(), mock.problemType(), mock.runtime(), mock.selectedLenses(), outputs,
                List.of(new DebateFinding(outputs.get(0).lensId(), outputs.get(1).lensId(), "정확도 개선과 현장 채택은 서로 다른 병목일 수 있다.",
                                "폐기 비율과 함께 권고 확인율 및 담당자 작업 시간을 측정한다."),
                        new DebateFinding(outputs.get(1).lensId(), outputs.get(0).lensId(), "기준선 품질이 낮으면 개선의 원인을 구분하기 어렵다.",
                                "실험 전에 품목별 기록 누락률과 비교군의 차이를 확인한다.")),
                List.of(new RedTeamFinding("자료가 부족한데도 구조화된 보고서를 효과의 증거로 받아들일 위험이 있다.", Severity.HIGH,
                                "실험 전 지표, 제외 기준, 중단 규칙을 문서화하고 실제 관찰과 가설을 구분한다."),
                        new RedTeamFinding("폐기량을 줄이는 과정에서 판매 기회를 잃을 수 있다.", Severity.MEDIUM,
                                "품절과 매출 변화도 함께 기록하며 안전 한도를 넘으면 실험을 멈춘다.")), plan, decision);
    }

    private static HarnessResult longResult() {
        HarnessResult base = normalResult();
        String longParagraph = IntStream.rangeClosed(1, 36).mapToObj(index -> "검증 항목 " + index
                + ": 품목별 변화와 담당자의 운영 부담을 함께 관찰하고, 계절성과 행사 효과를 구분할 수 있도록 원자료와 비교 조건을 기록한다.")
                .collect(Collectors.joining(" "));
        List<String> longData = IntStream.rangeClosed(1, 30)
                .mapToObj(index -> "자료 " + index + ": 날짜, 품목, 입고량, 판매량, 폐기량과 누락 사유를 기록한다.").toList();
        var oldDecision = base.finalDecision();
        var decision = new FinalDecision(oldDecision.coreHypothesis() + "\n\n" + longParagraph,
                oldDecision.weakestAssumption(), longData, oldDecision.firstExperiment() + "\n\n" + longParagraph,
                oldDecision.successCriteria(), oldDecision.failureCriteria(), oldDecision.nextDecision(), oldDecision.marketEntryWedge());
        List<LensOutput> outputs = new ArrayList<>(base.lensOutputs());
        LensOutput first = outputs.getFirst();
        outputs.set(0, new LensOutput(first.lensId(), first.coreView() + "\n\n" + longParagraph,
                List.of("길이검증한글연속문자".repeat(135)), List.of("unbrokenExperimentalConstraint".repeat(70)),
                longData, first.counterarguments(), first.experimentSuggestions(), 0.67325));
        var oldPlan = base.experimentPlan();
        var plan = new ExperimentPlan(oldPlan.hypothesis(), oldPlan.metric(), oldPlan.requiredData(), null, null,
                oldPlan.duration(), oldPlan.successCriteria(), oldPlan.failureCriteria(),
                List.of("특수 문자 원문 보존: \uDBFF\uDFFF 및 잘못된 단일 서로게이트 \uD800", longParagraph));
        var lastDebate = new DebateFinding(outputs.get(0).lensId(), outputs.get(1).lensId(), longParagraph,
                longParagraph + "\n\n모든 긴 항목의 마지막 결정 기록.");
        return new HarnessResult(base.problem(), base.problemType(), base.runtime(), base.selectedLenses(), outputs,
                List.of(lastDebate), List.of(new RedTeamFinding(longParagraph, Severity.LOW, "기록의 마지막 항목까지 확인하고 사전 중단 규칙을 적용한다.")),
                plan, decision);
    }

    private static String normalize(String value) {
        return value.replaceAll("\\s+", "");
    }

    private static final class BodyTextStripper extends PDFTextStripper {
        BodyTextStripper() throws IOException {}

        @Override
        protected void processTextPosition(TextPosition text) {
            float baseline = text.getYDirAdj();
            if (baseline > 110 && baseline < PDRectangle.A4.getHeight() - 65) {
                super.processTextPosition(text);
            }
        }
    }
}
