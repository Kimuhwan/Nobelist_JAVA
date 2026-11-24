package com.scientificlens.harness.core;

import com.scientificlens.harness.domain.HarnessResult;
import com.scientificlens.harness.domain.LensOutput;
import com.scientificlens.harness.domain.LensProfile;
import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;

/** 완료된 분석의 가설, 실험 계획, 반론과 결정을 검색 가능한 PDF로 저장합니다. */
public final class PdfReportWriter {
    private PdfReportWriter() {}

    /**
     * 한글 글꼴을 포함한 A4 보고서를 작성하고, 생성이 끝나면 지정한 파일로 옮깁니다.
     * @param result 화면에서 완료했거나 보관함에서 다시 연 분석
     * @param destination PDF를 저장할 경로
     * @throws IOException 글꼴을 읽거나 PDF를 저장할 수 없는 경우
     */
    public static void write(HarnessResult result, Path destination) throws IOException {
        Objects.requireNonNull(result, "result");
        Path output = Objects.requireNonNull(destination, "destination").toAbsolutePath();
        Path temporary = Files.createTempFile(output.getParent(), ".scientific-lens-", ".pdf");
        try {
            try (PDDocument document = new PDDocument(); Composer report = new Composer(document)) {
                document.getDocumentInformation().setTitle("Scientific Lens Harness - 연구 검증 계획");
                document.getDocumentInformation().setAuthor("Scientific Lens Harness");
                document.getDocumentInformation().setSubject(result.problem().question());
                document.getDocumentInformation().setCreator("Scientific Lens Harness / Apache PDFBox");
                document.getDocumentCatalog().setLanguage("ko-KR");
                render(result, report);
                report.finish();
                document.save(temporary.toFile());
            }
            try {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void render(HarnessResult result, Composer report) throws IOException {
        var decision = result.finalDecision();
        var plan = result.experimentPlan();
        report.masthead();
        report.field("질문", result.problem().question());
        report.field("분석 출처", "모드: " + result.problem().mode().value()
                + "  |  문제 유형: " + result.problemType().value() + "  |  런타임: " + result.runtime());
        if (result.problem().domainHint() != null) {
            report.field("사용자 도메인 힌트", result.problem().domainHint());
        }
        report.field("선택된 방법론 렌즈", String.join(", ", result.selectedLenses()));
        report.note("분석 생성 완료 · 가설 미검증",
                "이 보고서는 검증 계획입니다. 분석 완료는 실험 수행이나 가설의 입증을 뜻하지 않습니다. "
                        + "성공·실패 기준은 실험 전 판단 규칙입니다.");

        report.section("01", "핵심 가설과 다음 결정");
        report.field("검증할 핵심 가설", decision.coreHypothesis());
        report.note("다음 의사결정", decision.nextDecision());

        report.section("02", "첫 번째 실험");
        report.paragraph(decision.firstExperiment());

        report.section("03", "데이터와 사전 판단 기준");
        report.list("필요 데이터", decision.requiredData());
        report.field("성공 기준 · 사전 선언", decision.successCriteria());
        report.field("실패 기준 · 사전 선언", decision.failureCriteria());

        report.section("04", "가장 약한 가정과 레드팀 비판");
        report.note("가장 약한 가정", decision.weakestAssumption());
        int risk = 1;
        for (var finding : result.redTeamFindings()) {
            report.subheading("위험 " + risk++ + "  /  " + finding.severity().value().toUpperCase(Locale.ROOT));
            report.field("쟁점", finding.issue());
            report.field("완화책", finding.mitigation());
        }

        report.section("05", "시장 진입 전략");
        report.paragraph(decision.marketEntryWedge());

        report.section("06", "전체 실험 설계");
        report.field("실험 가설", plan.hypothesis());
        report.field("측정 지표", plan.metric());
        report.list("실험에 필요한 데이터", plan.requiredData());
        report.field("대조군", plan.controlGroup() == null ? "지정되지 않음" : plan.controlGroup());
        report.field("처치군", plan.treatmentGroup() == null ? "지정되지 않음" : plan.treatmentGroup());
        report.field("계획된 기간", plan.duration());
        report.field("실험 성공 기준 · 사전 선언", plan.successCriteria());
        report.field("실험 실패 기준 · 사전 선언", plan.failureCriteria());
        report.list("실험 위험 요소", plan.risks());

        report.section("07", "선택된 렌즈별 분석");
        report.paragraph("과학자 이름은 방법론 렌즈를 식별합니다. 실제 인물의 사적인 생각을 재현하거나 "
                + "권위를 근거로 결론을 정당화하지 않습니다. 확신도는 모델의 자체 평가이며 검증 확률이 아닙니다.");
        Map<String, LensProfile> profiles = new HashMap<>();
        LensRegistry.load().forEach(profile -> profiles.put(profile.lensId(), profile));
        int lens = 1;
        for (LensOutput output : result.lensOutputs()) {
            LensProfile profile = profiles.get(output.lensId());
            report.subheading(lens++ + ". " + (profile == null ? output.lensId() : profile.displayName())
                    + "  [" + output.lensId() + "]");
            if (profile != null) {
                report.field("사고 방법", profile.thinkingStyle());
                report.field("분석 책임", profile.responsibility());
                report.field("라우팅 태그", String.join(", ", profile.routingTags()));
                report.field("방법론 지침", profile.promptGuidance());
            }
            report.field("핵심 관점", output.coreView());
            report.list("가설", output.hypotheses());
            report.list("가정", output.assumptions());
            report.list("필요한 증거", output.evidenceNeeded());
            report.list("반론", output.counterarguments());
            report.list("실험 제안", output.experimentSuggestions());
            report.field("확신도 · 모델 자체 평가", Double.toString(output.confidence()) + " / 1.0 (검증 확률 아님)");
        }

        report.section("08", "렌즈 간 토론");
        int debate = 1;
        for (var finding : result.debateFindings()) {
            int index = debate++;
            report.subheading("토론 " + index + "  /  " + finding.sourceLensId() + " -> " + finding.targetLensId());
            report.field("우려", finding.concern());
            report.field("수정 제안", finding.suggestedRevision());
            if (!result.lensRevisions().isEmpty()) {
                var revision = result.lensRevisions().get(index - 1);
                report.field("실제 답변", revision.response());
                report.paragraph(revision.changedFields().isEmpty() ? "분석 값 유지 · 변경된 항목 없음"
                        : "실제 수정 전·후 · " + revision.changedFields().size() + "개 항목 변경");
                for (String field : revision.changedFields()) {
                    report.subheading(Reporting.revisionFieldLabel(field));
                    report.field("수정 전", Reporting.lensFieldValue(revision.before(), field));
                    report.field("수정 후", Reporting.lensFieldValue(revision.after(), field));
                }
            } else {
                report.paragraph("이 보고서 형식에는 실제 수정 전·후 분석 값이 저장되지 않았습니다.");
            }
        }
        if (!result.agentEvents().isEmpty()) {
            report.section("09", "에이전트 호출·토론 기록");
            report.paragraph("오케스트레이터의 공개 작업 지시와 실제 에이전트 답변입니다. 내부 추론 과정은 포함하지 않습니다.");
            for (var event : result.agentEvents()) {
                report.subheading(event.sequence() + ". " + Reporting.agentLabel(event.actor())
                        + " -> " + Reporting.agentLabel(event.recipient()));
                report.field("진행 단계", Reporting.actionLabel(event.action()));
                report.paragraph(event.content());
            }
        }
        report.note("보고서 읽기 안내", "이 문서는 사용자 질문과 생성된 분석 값을 담은 검증 계획입니다. "
                + "실험의 수행 결과는 포함하지 않습니다. 글꼴에 없는 문자는 [U+XXXX] 형태로 Unicode 코드를 "
                + "보존하여 표시합니다.");
    }

    private static final class Composer implements AutoCloseable {
        private static final float MARGIN = 48;
        private static final float WIDTH = PDRectangle.A4.getWidth() - MARGIN * 2;
        private static final float BOTTOM = 68;
        private static final float BODY = 11.5f;
        private static final float LEADING = 18;
        private static final Color INK = new Color(0x10, 0x24, 0x3d);
        private static final Color BLUE = new Color(0x23, 0x5e, 0xaa);
        private static final Color PAPER = new Color(0xf3, 0xf6, 0xfa);
        private static final Color MUTED = new Color(0x50, 0x64, 0x7c);
        private static final Color RULE = new Color(0xd9, 0xe3, 0xee);
        private final PDDocument document;
        private final PDType0Font regular;
        private final PDType0Font bold;
        private final PDDocumentOutline outline = new PDDocumentOutline();
        private final Map<PDType0Font, Map<Integer, String>> glyphs = new HashMap<>();
        private PDPage page;
        private PDPageContentStream stream;
        private float y;
        private String currentSection = "보고서";

        Composer(PDDocument document) throws IOException {
            this.document = document;
            regular = loadFont(document, "/fonts/NotoSansKR-Regular.ttf");
            bold = loadFont(document, "/fonts/NotoSansKR-Bold.ttf");
            document.getDocumentCatalog().setDocumentOutline(outline);
            newPage(false);
        }

        private static PDType0Font loadFont(PDDocument document, String resource) throws IOException {
            try (InputStream font = PdfReportWriter.class.getResourceAsStream(resource)) {
                if (font == null) {
                    throw new IOException("Missing bundled PDF font: " + resource);
                }
                return PDType0Font.load(document, font, true);
            }
        }

        void masthead() throws IOException {
            text("SCIENTIFIC LENS HARNESS", bold, 10, BLUE, MARGIN, y - 10);
            y -= 28;
            text("연구 검증 계획", bold, 28, INK, MARGIN, y - 28);
            y -= 47;
            text("가설에서 첫 실험과 다음 결정까지", regular, BODY, MUTED, MARGIN, y - BODY);
            y -= 32;
            line(y, BLUE, 1.2f);
            y -= 22;
        }

        void section(String number, String title) throws IOException {
            currentSection = number + "  " + title;
            if (y - 172 < BOTTOM) {
                newPage(false);
            }
            y -= 20;
            line(y, RULE, 0.6f);
            y -= 18;
            PDOutlineItem bookmark = new PDOutlineItem();
            bookmark.setTitle(currentSection);
            PDPageXYZDestination destination = new PDPageXYZDestination();
            destination.setPage(page);
            destination.setTop(Math.round(y + 18));
            bookmark.setDestination(destination);
            outline.addLast(bookmark);
            text(number, bold, 10, BLUE, MARGIN, y - 18);
            text(title, bold, 18, INK, MARGIN + 31, y - 18);
            y -= 39;
        }

        void subheading(String title) throws IOException {
            List<String> lines = wrap(title, bold, 13.5f, WIDTH);
            ensure(14 + Math.min(2, lines.size()) * 21 + 28 + LEADING * 3);
            y -= 14;
            for (int index = 0; index < lines.size(); index++) {
                ensure(21 + (index == lines.size() - 1 ? 28 + LEADING * 3 : 21));
                text(lines.get(index), bold, 13.5f, BLUE, MARGIN, y - 13.5f);
                y -= 21;
            }
            y -= 8;
        }

        void field(String label, String value) throws IOException {
            List<String> lines = wrap(value, regular, BODY, WIDTH);
            label(label, Math.min(3, lines.size()) * LEADING);
            bodyLines(lines, MARGIN, false);
            y -= 10;
        }

        void paragraph(String value) throws IOException {
            bodyLines(wrap(value, regular, BODY, WIDTH), MARGIN, false);
            y -= 12;
        }

        void list(String label, List<String> values) throws IOException {
            List<String> first = wrap(values.getFirst(), regular, BODY, WIDTH - 15);
            label(label, Math.min(3, first.size()) * LEADING);
            for (String value : values) {
                List<String> lines = wrap(value, regular, BODY, WIDTH - 15);
                ensure(Math.min(3, lines.size()) * LEADING);
                text("-", regular, BODY, BLUE, MARGIN, y - BODY);
                bodyLines(lines, MARGIN + 15, false);
                y -= 5;
            }
            y -= 8;
        }

        void note(String label, String value) throws IOException {
            List<String> lines = wrap(value, regular, BODY, WIDTH - 28);
            ensure(33 + Math.min(3, lines.size()) * LEADING);
            label(label, Math.min(3, lines.size()) * LEADING);
            bodyLines(lines, MARGIN + 14, true);
            y -= 13;
        }

        private void label(String label, float followingHeight) throws IOException {
            ensure(20 + followingHeight);
            text(label, bold, 10.5f, MUTED, MARGIN, y - 10.5f);
            y -= 20;
        }

        private void bodyLines(List<String> lines, float x, boolean tinted) throws IOException {
            int index = 0;
            while (index < lines.size()) {
                int remaining = lines.size() - index;
                int count = Math.min(remaining, (int) ((y - BOTTOM) / LEADING));
                if (remaining - count == 1 && count > 1) {
                    count--;
                }
                if (count < Math.min(2, remaining)) {
                    newPage(true);
                    continue;
                }
                for (int end = index + count; index < end; index++) {
                    if (tinted) {
                        stream.setNonStrokingColor(PAPER);
                        stream.addRect(MARGIN, y - LEADING, WIDTH, LEADING);
                        stream.fill();
                        stream.setStrokingColor(BLUE);
                        stream.setLineWidth(2);
                        stream.moveTo(MARGIN, y);
                        stream.lineTo(MARGIN, y - LEADING);
                        stream.stroke();
                    }
                    text(lines.get(index), regular, BODY, INK, x, y - BODY);
                    y -= LEADING;
                }
            }
        }

        private void ensure(float height) throws IOException {
            if (y - height < BOTTOM) {
                newPage(true);
            }
        }

        private void newPage(boolean continuation) throws IOException {
            close();
            page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            y = PDRectangle.A4.getHeight() - MARGIN;
            if (document.getNumberOfPages() > 1) {
                text("SCIENTIFIC LENS HARNESS", bold, 9, BLUE, MARGIN, y - 9);
                text("RESEARCH REPORT", regular, 9, MUTED, MARGIN + WIDTH - 95, y - 9);
                y -= 23;
                line(y, RULE, 0.6f);
                y -= 16;
                if (continuation) {
                    text(currentSection + " / 계속", regular, 10, MUTED, MARGIN, y - 10);
                    y -= 28;
                } else {
                    y -= 9;
                }
            }
        }

        private void line(float at, Color color, float thickness) throws IOException {
            stream.setStrokingColor(color);
            stream.setLineWidth(thickness);
            stream.moveTo(MARGIN, at);
            stream.lineTo(MARGIN + WIDTH, at);
            stream.stroke();
        }

        private void text(String value, PDType0Font font, float size, Color color, float x, float baseline)
                throws IOException {
            if (value.isEmpty()) {
                return;
            }
            stream.beginText();
            stream.setFont(font, size);
            stream.setNonStrokingColor(color);
            stream.newLineAtOffset(x, baseline);
            stream.showText(printable(value, font));
            stream.endText();
        }

        private String printable(String value, PDType0Font font) throws IOException {
            Map<Integer, String> cache = glyphs.computeIfAbsent(font, ignored -> new HashMap<>());
            StringBuilder result = new StringBuilder();
            for (int offset = 0; offset < value.length();) {
                int point = value.codePointAt(offset);
                offset += Character.charCount(point);
                String display = cache.get(point);
                if (display == null) {
                    display = new String(Character.toChars(point));
                    try {
                        font.encode(display);
                    } catch (IllegalArgumentException e) {
                        display = String.format(Locale.ROOT, "[U+%04X]", point);
                    }
                    cache.put(point, display);
                }
                result.append(display);
            }
            return result.toString();
        }

        private List<String> wrap(String value, PDType0Font font, float size, float width) throws IOException {
            List<String> lines = new ArrayList<>();
            for (String paragraph : value.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
                String safe = printable(paragraph, font);
                if (safe.isEmpty()) {
                    lines.add("");
                    continue;
                }
                int start = 0;
                while (start < safe.length()) {
                    float used = 0;
                    int end = start;
                    int whitespace = -1;
                    while (end < safe.length()) {
                        int point = safe.codePointAt(end);
                        int next = end + Character.charCount(point);
                        float advance = font.getStringWidth(safe.substring(end, next)) * size / 1000;
                        if (used + advance > width && end > start) {
                            break;
                        }
                        used += advance;
                        end = next;
                        if (Character.isWhitespace(point) || Character.isSpaceChar(point)) {
                            whitespace = end;
                        }
                    }
                    if (end < safe.length() && whitespace > start) {
                        end = whitespace;
                    }
                    lines.add(safe.substring(start, end).stripTrailing());
                    start = end;
                }
            }
            return lines;
        }

        void finish() throws IOException {
            close();
            int pages = document.getNumberOfPages();
            for (int index = 0; index < pages; index++) {
                page = document.getPage(index);
                stream = new PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true);
                line(52, RULE, 0.6f);
                text("검증 계획 · 실험 결과 아님", regular, 8, MUTED, MARGIN, 32);
                String folio = (index + 1) + " / " + pages;
                float folioWidth = regular.getStringWidth(folio) * 9 / 1000;
                text(folio, regular, 9, MUTED, MARGIN + WIDTH - folioWidth, 32);
                close();
            }
            outline.openNode();
        }

        @Override
        public void close() throws IOException {
            if (stream != null) {
                stream.close();
                stream = null;
            }
        }
    }
}
