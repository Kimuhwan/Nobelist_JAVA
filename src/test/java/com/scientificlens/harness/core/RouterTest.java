package com.scientificlens.harness.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.scientificlens.harness.domain.LensProfile;
import com.scientificlens.harness.domain.Mode;
import com.scientificlens.harness.domain.ProblemType;
import com.scientificlens.harness.domain.UserProblem;
import java.util.List;
import org.junit.jupiter.api.Test;

class RouterTest {
    private final List<LensProfile> profiles = LensRegistry.load();

    @Test
    void registryHasFifteenUniqueLenses() {
        assertEquals(15, profiles.size());
        assertEquals("einstein", LensRegistry.get("einstein").lensId());
        assertThrows(IllegalArgumentException.class, () -> LensRegistry.get("nobody"));
    }

    @Test
    void fastModeSelectsFiveLensesInOrder() {
        var selected = Router.select(new UserProblem("test", Mode.FAST), profiles);
        assertEquals(List.of("feynman", "newton", "pasteur", "oppenheimer", "galileo"),
                selected.stream().map(LensProfile::lensId).toList());
    }

    @Test
    void councilModeSelectsAllLenses() {
        assertEquals(15, Router.select(new UserProblem("test", Mode.COUNCIL), profiles).size());
    }

    @Test
    void researchProductValidationRoutesExpectedLenses() {
        var problem = new UserProblem("AI product prototype", Mode.RESEARCH);
        assertEquals(ProblemType.PRODUCT_VALIDATION, Router.classify(problem));
        assertEquals("newton", Router.select(problem, profiles).getFirst().lensId());
    }

    @Test
    void classifierPriorityMatchesPython() {
        assertEquals(ProblemType.RISK_ETHICS, Router.classify(new UserProblem("student data service", Mode.RESEARCH)));
        // Same as Python: only English "student" is a risk keyword, so Korean "학생" falls through to 서비스.
        assertEquals(ProblemType.PRODUCT_VALIDATION, Router.classify(new UserProblem("학생 데이터 서비스", Mode.RESEARCH)));
        assertEquals(ProblemType.BUSINESS_SYSTEM, Router.classify(new UserProblem("B2B SaaS 스타트업 사업", Mode.RESEARCH)));
        assertEquals(ProblemType.RESEARCH_HYPOTHESIS, Router.classify(new UserProblem("가설을 검증", Mode.RESEARCH)));
        assertEquals(ProblemType.GENERAL, Router.classify(new UserProblem("hello", Mode.RESEARCH)));
        assertEquals(ProblemType.RISK_ETHICS,
                Router.classify(new UserProblem("hello", Mode.RESEARCH, "privacy")));
    }

    @Test
    void invalidModeRejected() {
        assertThrows(IllegalArgumentException.class, () -> Mode.fromValue("invalid"));
    }

    @Test
    void blankQuestionRejected() {
        assertThrows(IllegalArgumentException.class, () -> new UserProblem("   ", Mode.FAST));
    }
}
