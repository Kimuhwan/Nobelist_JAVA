package com.scientificlens.harness.domain;

import java.util.List;

/** The final decision contract: every field is mandatory (see AGENTS.md review guidelines). */
public record FinalDecision(
        String coreHypothesis,
        String weakestAssumption,
        List<String> requiredData,
        String firstExperiment,
        String successCriteria,
        String failureCriteria,
        String nextDecision,
        String marketEntryWedge) {
    public FinalDecision {
        coreHypothesis = Checks.text(coreHypothesis, "core_hypothesis");
        weakestAssumption = Checks.text(weakestAssumption, "weakest_assumption");
        requiredData = Checks.items(requiredData, "required_data");
        firstExperiment = Checks.text(firstExperiment, "first_experiment");
        successCriteria = Checks.text(successCriteria, "success_criteria");
        failureCriteria = Checks.text(failureCriteria, "failure_criteria");
        nextDecision = Checks.text(nextDecision, "next_decision");
        marketEntryWedge = Checks.text(marketEntryWedge, "market_entry_wedge");
    }
}
