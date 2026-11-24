package com.scientificlens.harness.domain;

import java.util.List;

/** {@code controlGroup} and {@code treatmentGroup} may be null (e.g. observational designs). */
public record ExperimentPlan(
        String hypothesis,
        String metric,
        List<String> requiredData,
        String controlGroup,
        String treatmentGroup,
        String duration,
        String successCriteria,
        String failureCriteria,
        List<String> risks) {
    public ExperimentPlan {
        hypothesis = Checks.text(hypothesis, "hypothesis");
        metric = Checks.text(metric, "metric");
        requiredData = Checks.items(requiredData, "required_data");
        controlGroup = Checks.optionalText(controlGroup);
        treatmentGroup = Checks.optionalText(treatmentGroup);
        duration = Checks.text(duration, "duration");
        successCriteria = Checks.text(successCriteria, "success_criteria");
        failureCriteria = Checks.text(failureCriteria, "failure_criteria");
        risks = Checks.items(risks, "risks");
    }
}
