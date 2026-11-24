package com.scientificlens.harness.runtime;

import com.scientificlens.harness.domain.CouncilAction;
import com.scientificlens.harness.domain.CouncilLimits;
import com.scientificlens.harness.domain.CouncilReply;
import com.scientificlens.harness.domain.CouncilState;
import com.scientificlens.harness.domain.DebateFinding;
import com.scientificlens.harness.domain.ExperimentPlan;
import com.scientificlens.harness.domain.FinalDecision;
import com.scientificlens.harness.domain.LensOutput;
import com.scientificlens.harness.domain.LensProfile;
import com.scientificlens.harness.domain.ProblemType;
import com.scientificlens.harness.domain.RedTeamFinding;
import com.scientificlens.harness.domain.UserProblem;
import java.util.List;

public interface CouncilRuntime extends HarnessRuntime {
    CouncilAction nextAction(UserProblem problem, ProblemType type, List<LensProfile> catalog,
            CouncilState state, CouncilLimits limits, StageContext ctx);

    LensOutput analyzeAgent(UserProblem problem, ProblemType type, LensProfile lens, String task, StageContext ctx);

    DebateFinding challenge(UserProblem problem, LensProfile source, LensOutput sourceOutput,
            LensOutput targetOutput, String task, StageContext ctx);

    CouncilReply respond(UserProblem problem, LensProfile target, LensOutput current,
            DebateFinding challenge, StageContext ctx);

    List<RedTeamFinding> review(UserProblem problem, CouncilState state, String task, StageContext ctx);

    ExperimentPlan experiment(UserProblem problem, CouncilState state, String task, StageContext ctx);

    FinalDecision conclude(UserProblem problem, CouncilState state, String task, StageContext ctx);
}
