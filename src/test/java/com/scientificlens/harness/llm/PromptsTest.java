package com.scientificlens.harness.llm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.scientificlens.harness.core.LensRegistry;
import com.scientificlens.harness.core.Router;
import com.scientificlens.harness.domain.Mode;
import com.scientificlens.harness.domain.UserProblem;
import org.junit.jupiter.api.Test;

class PromptsTest {
    @Test
    void hangulRatioSeparatesKoreanFromEnglishJson() {
        String korean = "{\"core_view\":\"기준선 데이터가 부족하면 효과를 검증할 수 없다\"}";
        String english = "{\"core_view\":\"Without baseline data the effect cannot be verified\"}";
        assertTrue(Prompts.hangulRatio(korean) > 0.3);
        assertTrue(Prompts.hangulRatio(english) < 0.3);
        // keys are stripped before checking, so a Korean value is not diluted by English keys
        String mixed = "{\"experiment_suggestions\":[\"소규모 파일럿\"],\"counterarguments\":[\"표본";
        assertTrue(Prompts.hangulRatio(Prompts.stringValues(mixed)) > 0.9);
    }

    @Test
    void koreanQuestionGetsKoreanReminder() {
        var problem = new UserProblem("AI로 농산물 폐기량을 줄일 수 있을까?", Mode.FAST);
        assertTrue(Prompts.expectsKorean(problem));
        assertTrue(Prompts.withLanguageReminder(problem, "x").contains("한국어"));
        assertFalse(Prompts.expectsKorean(new UserProblem("Can AI reduce food waste?", Mode.FAST)));
    }

    @Test
    void promptsNeverAskForImpersonation() {
        var problem = new UserProblem("test", Mode.FAST);
        assertTrue(Prompts.system(problem).contains("Never speak as the scientist"));
        String lensPrompt = Prompts.lens(problem, Router.classify(problem), LensRegistry.get("einstein"));
        assertTrue(lensPrompt.contains("Einstein Lens"));
    }

    @Test
    void debateSchemaRestrictsIdsToSelectedLenses() {
        String schema = Prompts.debateSchema(java.util.List.of("newton", "galileo"));
        assertTrue(schema.contains("\"enum\":[\"newton\",\"galileo\"]"));
    }
}
