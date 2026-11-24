package com.scientificlens.harness.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.scientificlens.harness.domain.HarnessResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Port of evals/graders.py: checks the final-decision contract and persona language. */
public final class Grader {
    public record GradeResult(String caseId, boolean passed, List<String> failures) {}

    private static final List<String> BANNED =
            List.of("i am einstein", "i am newton", "actual thoughts", "private thoughts", "나는 아인슈타인");

    private Grader() {}

    public static GradeResult grade(String caseId, HarnessResult result, List<String> mustInclude) {
        List<String> failures = new ArrayList<>();
        JsonNode decision = Json.MAPPER.valueToTree(result.finalDecision());
        for (String field : mustInclude) {
            JsonNode value = decision.get(field);
            if (value == null || value.isNull() || (value.isTextual() && value.asText().isBlank())
                    || (value.isArray() && value.isEmpty())) {
                failures.add("missing final field: " + field);
            }
        }
        if (result.redTeamFindings().isEmpty()) {
            failures.add("missing red-team findings");
        }
        if (result.experimentPlan().failureCriteria().isBlank()) {
            failures.add("missing experiment failure criteria");
        }
        if (containsPersonaLanguage(result)) {
            failures.add("contains banned persona language");
        }
        return new GradeResult(caseId, failures.isEmpty(), List.copyOf(failures));
    }

    public static boolean containsPersonaLanguage(HarnessResult result) {
        String haystack = Json.write(result).toLowerCase(Locale.ROOT);
        return BANNED.stream().anyMatch(haystack::contains);
    }
}
