package com.scientificlens.harness.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scientificlens.harness.core.Json;
import com.scientificlens.harness.core.Orchestrator;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LensRevisionTest {
    @Test
    void responseAndSnapshotsRemainExactAndChangesUseCanonicalFieldOrder() {
        LensOutput before = output("target", "Original", List.of("First", "Second"), 0.5);
        LensOutput after = new LensOutput("target", "Revised", List.of("Second", "First"),
                List.of("Revised assumption"), List.of("Revised evidence"), List.of("Revised counterargument"),
                List.of("Revised experiment"), 0.7);
        String response = "  Accepted response with whitespace\nFull public response.\n";
        LensRevision revision = new LensRevision(finding("source", "target"), response, before, after);

        assertEquals(response, revision.response());
        assertSame(before, revision.before());
        assertSame(after, revision.after());
        assertEquals(List.of("core_view", "hypotheses", "assumptions", "evidence_needed", "counterarguments",
                "experiment_suggestions", "confidence"), revision.changedFields());
        assertThrows(UnsupportedOperationException.class, () -> revision.changedFields().clear());
        assertThrows(UnsupportedOperationException.class, () -> revision.before().hypotheses().clear());
    }

    @Test
    void proseDoesNotCreateChangesAndLongTextAndListOrderUseExactEquality() {
        LensOutput before = output("target", "x".repeat(3000) + "A", List.of("First", "Second"), 0.5);
        LensOutput equal = output("target", before.coreView(), new ArrayList<>(before.hypotheses()), 0.5);
        assertEquals(List.of(), new LensRevision(finding("source", "target"),
                "I changed the hypothesis, evidence, and confidence.", before, equal).changedFields());
        LensOutput after = output("target", "x".repeat(3000) + "B", List.of("Second", "First"), 0.5);
        assertEquals(List.of("core_view", "hypotheses"),
                new LensRevision(finding("source", "target"), "Accepted response", before, after).changedFields());
    }

    @Test
    void revisionRejectsBlankResponseSelfChallengeAndIncorrectSnapshotIds() {
        LensOutput target = output("target", "Original", List.of("Hypothesis"), 0.5);
        LensOutput other = output("other", "Original", List.of("Hypothesis"), 0.5);
        for (String response : new String[] {null, "", " \n\t"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new LensRevision(finding("source", "target"), response, target, target));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new LensRevision(finding("target", "target"), "Response", target, target));
        assertThrows(IllegalArgumentException.class,
                () -> new LensRevision(finding("source", "target"), "Response", other, target));
        assertThrows(IllegalArgumentException.class,
                () -> new LensRevision(finding("source", "target"), "Response", target, other));
        assertThrows(NullPointerException.class, () -> new LensRevision(null, "Response", target, target));
        assertThrows(NullPointerException.class,
                () -> new LensRevision(finding("source", "target"), "Response", null, target));
        assertThrows(NullPointerException.class,
                () -> new LensRevision(finding("source", "target"), "Response", target, null));
    }

    @Test
    void interleavedRepeatedTargetChainsRoundTripAndResultCopiesRevisionList() throws Exception {
        HarnessResult base = base();
        String source = base.selectedLenses().get(0);
        LensOutput firstTarget = base.lensOutputs().get(1);
        LensOutput secondTarget = base.lensOutputs().get(2);
        LensRevision first = revision(finding(source, firstTarget.lensId()), firstTarget, "First revision");
        LensRevision second = revision(finding(firstTarget.lensId(), secondTarget.lensId()), secondTarget, "Other target revision");
        LensRevision third = revision(finding(secondTarget.lensId(), firstTarget.lensId()), first.after(), "Final revision");
        List<LensRevision> mutable = new ArrayList<>(List.of(first, second, third));
        List<LensOutput> outputs = finalOutputs(base, mutable);
        HarnessResult result = result(base, mutable.stream().map(LensRevision::finding).toList(), outputs, mutable);
        mutable.clear();

        assertEquals(List.of(first, second, third), result.lensRevisions());
        assertSame(first.after(), result.lensRevisions().getLast().before());
        assertEquals(third.after(), result.lensOutputs().get(1));
        assertEquals(second.after(), result.lensOutputs().get(2));
        assertThrows(UnsupportedOperationException.class, () -> result.lensRevisions().clear());
        assertEquals(result, Json.MAPPER.readValue(Json.write(result), HarnessResult.class));
        ObjectNode json = Json.MAPPER.valueToTree(result);
        ((ObjectNode) json.path("lens_revisions").get(2).path("before")).put("core_view", "Broken chain");
        assertThrows(JsonProcessingException.class, () -> Json.MAPPER.treeToValue(json, HarnessResult.class));
    }

    @Test
    void resultRejectsRevisionCountFindingOrderAndFindingValueMismatch() {
        HarnessResult base = base();
        LensOutput target = base.lensOutputs().get(1);
        LensRevision first = revision(finding(base.selectedLenses().get(0), target.lensId()), target, "First revision");
        LensRevision second = revision(finding(base.selectedLenses().get(2), target.lensId()), first.after(), "Second revision");
        List<LensRevision> revisions = List.of(first, second);
        List<LensOutput> outputs = finalOutputs(base, revisions);
        assertThrows(IllegalArgumentException.class,
                () -> result(base, List.of(first.finding()), outputs, revisions));
        assertThrows(IllegalArgumentException.class,
                () -> result(base, List.of(second.finding(), first.finding()), outputs, revisions));
        DebateFinding changedFinding = new DebateFinding(first.finding().sourceLensId(), target.lensId(),
                "Different concern", first.finding().suggestedRevision());
        assertThrows(IllegalArgumentException.class,
                () -> result(base, List.of(changedFinding, second.finding()), outputs, revisions));
    }

    @Test
    void resultRejectsBrokenChainsIncorrectFinalOutputAndUnselectedIds() {
        HarnessResult base = base();
        LensOutput target = base.lensOutputs().get(1);
        LensRevision first = revision(finding(base.selectedLenses().get(0), target.lensId()), target, "First revision");
        LensRevision broken = revision(finding(base.selectedLenses().get(2), target.lensId()), target, "Second revision");
        assertThrows(IllegalArgumentException.class,
                () -> result(base, List.of(first.finding(), broken.finding()), finalOutputs(base, List.of(broken)), List.of(first, broken)));
        assertThrows(IllegalArgumentException.class,
                () -> result(base, List.of(first.finding()), base.lensOutputs(), List.of(first)));
        LensRevision unknownSource = revision(finding("unselected", target.lensId()), target, "Revision");
        assertThrows(IllegalArgumentException.class,
                () -> result(base, List.of(unknownSource.finding()), finalOutputs(base, List.of(unknownSource)), List.of(unknownSource)));
        LensOutput unknown = output("unselected", "Original", List.of("Hypothesis"), 0.5);
        LensRevision unknownTarget = revision(finding(base.selectedLenses().get(0), "unselected"), unknown, "Revision");
        assertThrows(IllegalArgumentException.class,
                () -> result(base, List.of(unknownTarget.finding()), base.lensOutputs(), List.of(unknownTarget)));
    }

    @Test
    void nullAndEmptyRevisionListsRemainLegacyReports() {
        HarnessResult base = base();
        assertTrue(base.lensRevisions().isEmpty());
        assertEquals(base, result(base, base.debateFindings(), base.lensOutputs(), null));
        assertEquals(base, result(base, base.debateFindings(), base.lensOutputs(), List.of()));
    }

    private static HarnessResult base() {
        return Orchestrator.analyzeMock(new UserProblem("Does a measured intervention improve the outcome?", Mode.FAST));
    }

    private static DebateFinding finding(String source, String target) {
        return new DebateFinding(source, target, "Measure the missing baseline", "Require a controlled comparison");
    }

    private static LensRevision revision(DebateFinding finding, LensOutput before, String view) {
        LensOutput after = new LensOutput(before.lensId(), view, before.hypotheses(), before.assumptions(),
                before.evidenceNeeded(), before.counterarguments(), before.experimentSuggestions(), before.confidence());
        return new LensRevision(finding, "Accepted public response: " + view, before, after);
    }

    private static LensOutput output(String id, String view, List<String> hypotheses, double confidence) {
        return new LensOutput(id, view, hypotheses, List.of("Assumption"), List.of("Evidence"),
                List.of("Counterargument"), List.of("Experiment"), confidence);
    }

    private static List<LensOutput> finalOutputs(HarnessResult base, List<LensRevision> revisions) {
        List<LensOutput> outputs = new ArrayList<>(base.lensOutputs());
        for (LensRevision revision : revisions) {
            for (int i = 0; i < outputs.size(); i++) {
                if (outputs.get(i).lensId().equals(revision.after().lensId())) {
                    outputs.set(i, revision.after());
                }
            }
        }
        return outputs;
    }

    private static HarnessResult result(HarnessResult base, List<DebateFinding> findings, List<LensOutput> outputs,
            List<LensRevision> revisions) {
        return new HarnessResult(base.problem(), base.problemType(), base.runtime(), base.selectedLenses(), outputs,
                findings, base.redTeamFindings(), base.experimentPlan(), base.finalDecision(), base.agentEvents(), revisions);
    }
}
