package com.scientificlens.harness.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.scientificlens.harness.domain.FinalDecision;
import com.scientificlens.harness.domain.HarnessResult;
import com.scientificlens.harness.domain.Mode;
import com.scientificlens.harness.domain.UserProblem;
import com.scientificlens.harness.runtime.MockRuntime;
import com.scientificlens.harness.runtime.StageContext;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class OrchestratorTest {
    @Test
    void mockPipelineReturnsEveryStage() {
        HarnessResult r = Orchestrator.analyzeMock(new UserProblem("AI로 농산물 폐기량을 줄일 수 있을까?", Mode.RESEARCH));
        assertTrue(r.selectedLenses().size() >= 5);
        assertEquals(r.selectedLenses().size(), r.lensOutputs().size());
        assertFalse(r.debateFindings().isEmpty());
        assertFalse(r.redTeamFindings().isEmpty());
        assertFalse(r.experimentPlan().successCriteria().isBlank());
        assertFalse(r.finalDecision().failureCriteria().isBlank());
        assertEquals("mock", r.runtime());
    }

    @Test
    void mockIsDeterministic() {
        var p = new UserProblem("test question", Mode.FAST);
        assertEquals(Reporting.json(Orchestrator.analyzeMock(p)), Reporting.json(Orchestrator.analyzeMock(p)));
    }

    @Test
    void progressReportsEveryStage() {
        List<Integer> steps = new ArrayList<>();
        new Orchestrator().run(new UserProblem("test", Mode.FAST), new MockRuntime(),
                (step, total, msg) -> steps.add(step), StageContext.NONE);
        assertEquals(5 + 4, steps.getLast());
    }

    @Test
    void cancellationStopsPipeline() {
        StageContext cancelled = new StageContext() {
            @Override
            public boolean isCancelled() {
                return true;
            }

            @Override
            public void onToken(String token) {}
        };
        assertThrows(CancellationException.class, () -> new Orchestrator()
                .run(new UserProblem("test", Mode.FAST), new MockRuntime(), Orchestrator.Progress.NONE, cancelled));
    }

    @Test
    void jsonUsesSnakeCaseAndContainsFinalContract() throws Exception {
        JsonNode node = Json.MAPPER.readTree(Reporting.json(Orchestrator.analyzeMock(new UserProblem("t", Mode.FAST))));
        assertEquals("fast", node.at("/problem/mode").asText());
        for (String field : List.of("core_hypothesis", "weakest_assumption", "required_data", "first_experiment",
                "success_criteria", "failure_criteria", "next_decision", "market_entry_wedge")) {
            assertTrue(node.at("/final_decision").has(field), field);
        }
        assertTrue(node.at("/red_team_findings/0/severity").asText().matches("low|medium|high"));
    }

    @Test
    void markdownContainsAllSections() {
        String md = Reporting.markdown(Orchestrator.analyzeMock(new UserProblem("test", Mode.RESEARCH)));
        for (String s : List.of("## 렌즈별 분석", "## 렌즈 간 토론", "## 레드팀 위험 평가", "## 첫 번째 실험 설계",
                "## 최종 의사결정 계약서", "핵심 가설", "가장 약한 가정", "실패 기준", "다음 의사결정", "시장 돌파구")) {
            assertTrue(md.contains(s), s);
        }
    }

    @Test
    void finalDecisionRejectsMissingFields() {
        assertThrows(IllegalArgumentException.class,
                () -> new FinalDecision("h", " ", List.of("d"), "e", "s", "f", "n", "w"));
        assertThrows(IllegalArgumentException.class,
                () -> new FinalDecision("h", "w", List.of(), "e", "s", "f", "n", "w"));
    }

    @Test
    void finalDecisionDeserializesFromSnakeCase() throws Exception {
        String raw = """
                {"core_hypothesis":"h","weakest_assumption":"w","required_data":["d"],"first_experiment":"e",
                 "success_criteria":"s","failure_criteria":"f","next_decision":"n","market_entry_wedge":"m"}""";
        assertEquals("w", Json.MAPPER.readValue(raw, FinalDecision.class).weakestAssumption());
    }
}
