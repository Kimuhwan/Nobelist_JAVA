package com.scientificlens.harness.runtime;

import com.scientificlens.harness.domain.DebateFinding;
import com.scientificlens.harness.domain.ExperimentPlan;
import com.scientificlens.harness.domain.FinalDecision;
import com.scientificlens.harness.domain.LensOutput;
import com.scientificlens.harness.domain.LensProfile;
import com.scientificlens.harness.domain.ProblemType;
import com.scientificlens.harness.domain.RedTeamFinding;
import com.scientificlens.harness.domain.Severity;
import com.scientificlens.harness.domain.UserProblem;
import java.util.List;

/** Deterministic, offline runtime. Same templates as the Python mock; used for tests and evals. */
public final class MockRuntime implements HarnessRuntime {
    @Override
    public String name() {
        return "mock";
    }

    @Override
    public LensOutput runLens(UserProblem problem, ProblemType type, LensProfile lens, StageContext ctx) {
        return new LensOutput(
                lens.lensId(),
                lens.displayName() + " frames this as: " + lens.responsibility(),
                List.of("If the intervention addresses the key constraint, then '"
                        + problem.question() + "' improves measurably."),
                List.of("The main bottleneck named by " + lens.lensId() + " is causal."),
                List.of("baseline measurement", "pilot segment definition", "counterfactual or comparison group"),
                List.of(lens.lensId() + " may over-weight one mechanism and miss external constraints."),
                List.of("Run a small controlled pilot focused on " + lens.lensId() + "'s causal claim."),
                0.66);
    }

    @Override
    public List<DebateFinding> debate(UserProblem problem, List<LensOutput> outputs, StageContext ctx) {
        LensOutput first = outputs.getFirst();
        LensOutput second = outputs.size() > 1 ? outputs.get(1) : first;
        return List.of(
                new DebateFinding(
                        first.lensId(),
                        second.lensId(),
                        "Mechanism clarity and operational adoption may be different bottlenecks.",
                        "Measure both outcome lift and workflow friction in the first pilot."),
                new DebateFinding(
                        second.lensId(),
                        first.lensId(),
                        "Baseline quality is the gating issue across lenses.",
                        "Collect baseline data before claiming intervention value."));
    }

    @Override
    public List<RedTeamFinding> redTeam(UserProblem problem, List<LensOutput> outputs, StageContext ctx) {
        String primary = outputs.getFirst().lensId();
        return List.of(
                new RedTeamFinding(
                        "The " + primary + " lens may produce a plausible story while baseline data for '"
                                + problem.question() + "' is weak.",
                        Severity.HIGH,
                        "Pre-register the baseline metric, exclusion criteria, and stop rule."),
                new RedTeamFinding(
                        "A structured report can be mistaken for proof.",
                        Severity.MEDIUM,
                        "Require success and failure criteria before the next decision."));
    }

    @Override
    public ExperimentPlan designExperiment(
            UserProblem problem,
            List<LensOutput> outputs,
            List<DebateFinding> debate,
            List<RedTeamFinding> redTeam,
            StageContext ctx) {
        String hypothesis = outputs.isEmpty() ? problem.question() : outputs.getFirst().hypotheses().getFirst();
        return new ExperimentPlan(
                hypothesis,
                "Measured reduction in the target problem within the pilot segment",
                List.of("baseline outcome data", "intervention exposure data", "cost and operational constraints"),
                "Comparable segment without the proposed intervention",
                "Segment using the proposed intervention",
                "2 to 4 weeks",
                "The treatment improves the target metric by at least 10% without new critical risks.",
                "The metric does not improve, data quality is insufficient, or risks outweigh benefits.",
                List.of("Measurement bias", "Confounding variables", "Operational burden"));
    }

    @Override
    public FinalDecision synthesize(
            UserProblem problem,
            List<LensOutput> outputs,
            ExperimentPlan plan,
            List<RedTeamFinding> redTeam,
            StageContext ctx) {
        return new FinalDecision(
                plan.hypothesis(),
                outputs.getFirst().assumptions().getFirst(),
                plan.requiredData(),
                "Run the pilot for '" + problem.question() + "' using " + plan.metric().toLowerCase() + ".",
                plan.successCriteria(),
                plan.failureCriteria(),
                "Scale, revise, or stop after comparing treatment and control results.",
                "Target small and mid-size produce distributors who cannot afford enterprise ERP solutions; "
                        + "incumbents focus on large retailers and are unlikely to retaliate in this segment, "
                        + "and a win here unlocks a reference case for broader market expansion.");
    }
}
