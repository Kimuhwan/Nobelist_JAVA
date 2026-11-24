package com.scientificlens.harness.domain;

import java.util.List;

public record CouncilState(
        List<AgentAssignment> assignments,
        List<LensOutput> outputs,
        List<DebateFinding> debates,
        List<RedTeamFinding> redTeam,
        ExperimentPlan experiment,
        int completedDebates,
        String feedback,
        List<AgentEvent> events) {
    public CouncilState {
        assignments = immutable(assignments);
        outputs = immutable(outputs);
        debates = immutable(debates);
        redTeam = immutable(redTeam);
        events = immutable(events);
        feedback = feedback == null ? "" : feedback.strip();
        if (completedDebates < 0 || completedDebates != debates.size()) {
            throw new IllegalArgumentException("completed_debates must match accepted debates");
        }
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
