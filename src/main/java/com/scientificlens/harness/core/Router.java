package com.scientificlens.harness.core;

import com.scientificlens.harness.domain.LensProfile;
import com.scientificlens.harness.domain.ProblemType;
import com.scientificlens.harness.domain.UserProblem;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Keyword classifier and lens selection. Faithful port of the Python router. */
public final class Router {
    public static final List<String> FAST_LENS_IDS =
            List.of("feynman", "newton", "pasteur", "oppenheimer", "galileo");
    public static final List<String> PRODUCT_LENS_IDS =
            List.of("newton", "galileo", "tesla", "pasteur", "curie", "feynman", "oppenheimer");
    public static final List<String> RESEARCH_LENS_IDS =
            List.of("einstein", "planck", "bohr", "schrodinger", "maxwell", "feynman", "galileo");
    public static final List<String> BUSINESS_LENS_IDS =
            List.of("darwin", "pasteur", "oppenheimer", "newton", "feynman", "curie");
    public static final List<String> RISK_LENS_IDS =
            List.of("oppenheimer", "hawking", "darwin", "pasteur", "bohr");

    private Router() {}

    public static ProblemType classify(UserProblem problem) {
        String haystack = (problem.question() + " " + (problem.domainHint() == null ? "" : problem.domainHint()))
                .toLowerCase(Locale.ROOT);
        if (containsAny(haystack, "risk", "ethic", "privacy", "student", "위험", "윤리")) {
            return ProblemType.RISK_ETHICS;
        }
        if (containsAny(haystack, "startup", "market", "business", "service", "사업", "시장")) {
            return ProblemType.BUSINESS_SYSTEM;
        }
        if (containsAny(haystack, "research", "hypothesis", "physics", "study", "연구", "가설")) {
            return ProblemType.RESEARCH_HYPOTHESIS;
        }
        if (containsAny(haystack, "product", "prototype", "ai", "앱", "제품", "서비스")) {
            return ProblemType.PRODUCT_VALIDATION;
        }
        return ProblemType.GENERAL;
    }

    public static List<LensProfile> select(UserProblem problem, List<LensProfile> profiles) {
        Map<String, LensProfile> byId =
                profiles.stream().collect(Collectors.toMap(LensProfile::lensId, Function.identity()));
        List<String> ids = switch (problem.mode()) {
            case FAST -> FAST_LENS_IDS;
            case RESEARCH -> researchLensesFor(classify(problem));
            case COUNCIL -> profiles.stream().map(LensProfile::lensId).toList();
        };
        return ids.stream()
                .map(id -> {
                    LensProfile profile = byId.get(id);
                    if (profile == null) {
                        throw new IllegalStateException("router references unknown lens: " + id);
                    }
                    return profile;
                })
                .toList();
    }

    static List<String> researchLensesFor(ProblemType type) {
        return switch (type) {
            case PRODUCT_VALIDATION, GENERAL -> PRODUCT_LENS_IDS;
            case RESEARCH_HYPOTHESIS -> RESEARCH_LENS_IDS;
            case BUSINESS_SYSTEM -> BUSINESS_LENS_IDS;
            case RISK_ETHICS -> RISK_LENS_IDS;
        };
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
