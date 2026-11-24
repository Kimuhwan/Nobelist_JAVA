package com.scientificlens.harness.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record HarnessResult(
        UserProblem problem,
        ProblemType problemType,
        String runtime,
        List<String> selectedLenses,
        List<LensOutput> lensOutputs,
        List<DebateFinding> debateFindings,
        List<RedTeamFinding> redTeamFindings,
        ExperimentPlan experimentPlan,
        FinalDecision finalDecision,
        List<AgentEvent> agentEvents,
        List<LensRevision> lensRevisions) {
    public HarnessResult {
        Objects.requireNonNull(problem, "problem");
        Objects.requireNonNull(problemType, "problem_type");
        runtime = Checks.text(runtime, "runtime");
        selectedLenses = Checks.items(selectedLenses, "selected_lenses");
        lensOutputs = Checks.nonEmpty(lensOutputs, "lens_outputs");
        debateFindings = Checks.nonEmpty(debateFindings, "debate_findings");
        redTeamFindings = Checks.nonEmpty(redTeamFindings, "red_team_findings");
        Objects.requireNonNull(experimentPlan, "experiment_plan");
        Objects.requireNonNull(finalDecision, "final_decision");
        agentEvents = agentEvents == null ? List.of() : List.copyOf(agentEvents);
        lensRevisions = lensRevisions == null ? List.of() : List.copyOf(lensRevisions);
        List<String> outputIds = lensOutputs.stream().map(LensOutput::lensId).toList();
        if (!outputIds.equals(selectedLenses)) {
            throw new IllegalArgumentException("selected_lenses must match lens_outputs order");
        }
        if (!lensRevisions.isEmpty()) {
            validateRevisions(lensRevisions, debateFindings, lensOutputs, selectedLenses);
        }
    }

    public HarnessResult(
            UserProblem problem,
            ProblemType problemType,
            String runtime,
            List<String> selectedLenses,
            List<LensOutput> lensOutputs,
            List<DebateFinding> debateFindings,
            List<RedTeamFinding> redTeamFindings,
            ExperimentPlan experimentPlan,
            FinalDecision finalDecision,
            List<AgentEvent> agentEvents) {
        this(problem, problemType, runtime, selectedLenses, lensOutputs, debateFindings, redTeamFindings,
                experimentPlan, finalDecision, agentEvents, List.of());
    }

    public HarnessResult(
            UserProblem problem,
            ProblemType problemType,
            String runtime,
            List<String> selectedLenses,
            List<LensOutput> lensOutputs,
            List<DebateFinding> debateFindings,
            List<RedTeamFinding> redTeamFindings,
            ExperimentPlan experimentPlan,
            FinalDecision finalDecision) {
        this(problem, problemType, runtime, selectedLenses, lensOutputs, debateFindings, redTeamFindings,
                experimentPlan, finalDecision, List.of(), List.of());
    }

    private static void validateRevisions(List<LensRevision> revisions, List<DebateFinding> findings,
            List<LensOutput> outputs, List<String> selectedIds) {
        if (revisions.size() != findings.size()) {
            throw new IllegalArgumentException("lens_revisions must match debate_findings count");
        }
        Map<String, LensOutput> latestByTarget = new LinkedHashMap<>();
        for (int i = 0; i < revisions.size(); i++) {
            LensRevision revision = revisions.get(i);
            DebateFinding finding = findings.get(i);
            if (!revision.finding().equals(finding)) {
                throw new IllegalArgumentException("lens_revisions must match debate_findings order and values");
            }
            if (!selectedIds.contains(finding.sourceLensId()) || !selectedIds.contains(finding.targetLensId())) {
                throw new IllegalArgumentException("revision source and target IDs must be selected lens IDs");
            }
            LensOutput preceding = latestByTarget.get(finding.targetLensId());
            if (preceding != null && !revision.before().equals(preceding)) {
                throw new IllegalArgumentException("repeated target revision before must equal the preceding after");
            }
            latestByTarget.put(finding.targetLensId(), revision.after());
        }
        for (LensOutput output : outputs) {
            LensOutput finalRevision = latestByTarget.get(output.lensId());
            if (finalRevision != null && !output.equals(finalRevision)) {
                throw new IllegalArgumentException("final target revision after must equal the accepted lens output");
            }
        }
    }
}
