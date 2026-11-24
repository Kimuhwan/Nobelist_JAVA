package com.scientificlens.harness.domain;

/** One lens challenging another lens's analysis. */
public record DebateFinding(String sourceLensId, String targetLensId, String concern, String suggestedRevision) {
    public DebateFinding {
        sourceLensId = Checks.text(sourceLensId, "source_lens_id");
        targetLensId = Checks.text(targetLensId, "target_lens_id");
        concern = Checks.text(concern, "concern");
        suggestedRevision = Checks.text(suggestedRevision, "suggested_revision");
    }
}
