package com.scientificlens.harness.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record LensRevision(DebateFinding finding, String response, LensOutput before, LensOutput after) {
    public LensRevision {
        Objects.requireNonNull(finding, "finding");
        if (response == null || response.isBlank()) {
            throw new IllegalArgumentException("response must not be blank");
        }
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        if (finding.sourceLensId().equals(finding.targetLensId())) {
            throw new IllegalArgumentException("revision source and target must be distinct");
        }
        if (!before.lensId().equals(finding.targetLensId()) || !after.lensId().equals(finding.targetLensId())) {
            throw new IllegalArgumentException("revision before and after must preserve the target lens ID");
        }
    }

    public List<String> changedFields() {
        List<String> changed = new ArrayList<>();
        if (!before.coreView().equals(after.coreView())) {
            changed.add("core_view");
        }
        if (!before.hypotheses().equals(after.hypotheses())) {
            changed.add("hypotheses");
        }
        if (!before.assumptions().equals(after.assumptions())) {
            changed.add("assumptions");
        }
        if (!before.evidenceNeeded().equals(after.evidenceNeeded())) {
            changed.add("evidence_needed");
        }
        if (!before.counterarguments().equals(after.counterarguments())) {
            changed.add("counterarguments");
        }
        if (!before.experimentSuggestions().equals(after.experimentSuggestions())) {
            changed.add("experiment_suggestions");
        }
        if (Double.compare(before.confidence(), after.confidence()) != 0) {
            changed.add("confidence");
        }
        return List.copyOf(changed);
    }
}
