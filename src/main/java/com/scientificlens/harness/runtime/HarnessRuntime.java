package com.scientificlens.harness.runtime;

import com.scientificlens.harness.domain.DebateFinding;
import com.scientificlens.harness.domain.ExperimentPlan;
import com.scientificlens.harness.domain.FinalDecision;
import com.scientificlens.harness.domain.LensOutput;
import com.scientificlens.harness.domain.LensProfile;
import com.scientificlens.harness.domain.ProblemType;
import com.scientificlens.harness.domain.RedTeamFinding;
import com.scientificlens.harness.domain.UserProblem;
import java.util.List;

/**
 * Produces each pipeline stage. The Python version only made {@code run_lens} pluggable; here every
 * stage is, so a real LLM can drive debate, red-team, experiment design and synthesis as well.
 */
public interface HarnessRuntime extends AutoCloseable {
    String name();

    LensOutput runLens(UserProblem problem, ProblemType type, LensProfile lens, StageContext ctx);

    List<DebateFinding> debate(UserProblem problem, List<LensOutput> outputs, StageContext ctx);

    List<RedTeamFinding> redTeam(UserProblem problem, List<LensOutput> outputs, StageContext ctx);

    ExperimentPlan designExperiment(
            UserProblem problem,
            List<LensOutput> outputs,
            List<DebateFinding> debate,
            List<RedTeamFinding> redTeam,
            StageContext ctx);

    FinalDecision synthesize(
            UserProblem problem,
            List<LensOutput> outputs,
            ExperimentPlan plan,
            List<RedTeamFinding> redTeam,
            StageContext ctx);

    @Override
    default void close() {}
}
