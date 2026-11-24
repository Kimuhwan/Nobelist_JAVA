package com.scientificlens.harness.domain;

import java.util.List;

/** Independent analysis produced by one lens. */
public record LensOutput(
        String lensId,
        String coreView,
        List<String> hypotheses,
        List<String> assumptions,
        List<String> evidenceNeeded,
        List<String> counterarguments,
        List<String> experimentSuggestions,
        double confidence) {
    public LensOutput {
        lensId = Checks.text(lensId, "lens_id");
        coreView = Checks.text(coreView, "core_view");
        hypotheses = Checks.items(hypotheses, "hypotheses");
        assumptions = Checks.items(assumptions, "assumptions");
        evidenceNeeded = Checks.items(evidenceNeeded, "evidence_needed");
        counterarguments = Checks.items(counterarguments, "counterarguments");
        experimentSuggestions = Checks.items(experimentSuggestions, "experiment_suggestions");
        if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be within [0, 1]");
        }
    }
}
