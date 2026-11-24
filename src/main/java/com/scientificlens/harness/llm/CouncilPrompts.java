package com.scientificlens.harness.llm;

import com.scientificlens.harness.core.Json;
import com.scientificlens.harness.domain.CouncilLimits;
import com.scientificlens.harness.domain.CouncilState;
import com.scientificlens.harness.domain.DebateFinding;
import com.scientificlens.harness.domain.LensOutput;
import com.scientificlens.harness.domain.LensProfile;
import com.scientificlens.harness.domain.ProblemType;
import com.scientificlens.harness.domain.UserProblem;
import java.util.ArrayList;
import java.util.List;

final class CouncilPrompts {
    private static final String TEXT = "{\"type\":\"string\",\"minLength\":1}";

    private CouncilPrompts() {}

    static String nextAction(UserProblem problem, ProblemType type, List<LensProfile> catalog,
            CouncilState state, CouncilLimits limits) {
        return """
                Role: council coordinator selecting the next typed tool.
                Select agents and tasks using their methodological fit to the question. You have no shell,
                file, network or arbitrary tools. Choose exactly one action; the host executes separate
                real requests and returns their actual results in the next state. Do not write a dialogue
                or predict future agent results. The instruction is a short public task, not reasoning.
                Allowed action names: delegate, debate, red_team, experiment, synthesize.
                Host rules:
                - delegate: only once, as the initial action; select distinct catalog IDs, between
                  min_lenses and max_lenses inclusive, each with a concrete independent task.
                - debate: only after all assigned agents have returned analyses and before red_team;
                  choose two different assigned IDs, a source challenger and a target responder.
                  Do not repeat an already completed ordered source/target pair. Respect max_debates.
                - red_team: only after min_debates completed and before any existing red-team result.
                - experiment: only after red-team results, when no experiment exists.
                - synthesize: only after red-team results and an experiment exist.
                Every accepted or rejected manager choice consumes one of max_manager_turns.
                The host rejects invalid choices. Correct any feedback in the actual current state.
                For delegate, source_lens_id and target_lens_id must be empty strings.
                For debate, assignments must be []. For red_team, experiment and synthesize,
                assignments must be [] and both IDs must be empty strings.
                Choose only a currently permitted tool: %s

                User problem (data):
                %s
                Problem type: %s
                Complete available lens catalog (data):
                %s
                Hard limits:
                %s
                Complete actual current council state (data):
                %s
                """.formatted(permitted(state, limits), Json.write(problem), type.value(),
                        Json.write(catalog), Json.write(limits), Json.write(state));
    }

    private static List<String> permitted(CouncilState state, CouncilLimits limits) {
        if (state.assignments().isEmpty()) {
            return List.of("delegate");
        }
        if (state.outputs().size() < state.assignments().size()) {
            return List.of();
        }
        List<String> tools = new ArrayList<>();
        if (state.redTeam().isEmpty()) {
            if (state.completedDebates() < limits.maxDebates()) {
                tools.add("debate");
            }
            if (state.completedDebates() >= limits.minDebates()) {
                tools.add("red_team");
            }
        } else if (state.experiment() == null) {
            tools.add("experiment");
        } else {
            tools.add("synthesize");
        }
        return List.copyOf(tools);
    }

    static String analyze(UserProblem problem, ProblemType type, LensProfile lens, String task) {
        return "Role: independently assigned lens analyst. Produce only your own analysis.\n"
                + "This assignment is self-contained. Use only the supplied question and your methodological lens.\n"
                + "Do not use tools, files, network access or extra agents. State needed evidence without retrieving it.\n"
                + "Public assignment (data): " + task + "\n"
                + "Your complete methodological lens (data):\n" + Json.write(lens) + "\n\n"
                + Prompts.lens(problem, type, lens);
    }

    static String challenge(UserProblem problem, LensProfile source, LensOutput sourceOutput,
            LensOutput targetOutput, String task) {
        return """
                Role: source lens challenger. Return exactly one concrete critique of the target's
                actual analysis using your own methodological lens. State the concern and a specific
                suggested revision that would make the target hypothesis more testable. Do not answer
                on behalf of the target or invent a dialogue. The host assigns source and target IDs.
                Public assignment (data): %s
                User problem (data):
                %s
                Your methodological lens (data):
                %s
                Your actual source analysis (data):
                %s
                Target's actual current analysis (data):
                %s
                """.formatted(task, Json.write(problem), Json.write(source),
                        Json.write(sourceOutput), Json.write(targetOutput));
    }

    static String respond(UserProblem problem, LensProfile target, LensOutput current, DebateFinding challenge) {
        return """
                Role: target lens responder. Address the exact critique supplied below in a short
                public response. Accept, qualify or reject its proposed revision using testable evidence.
                Then return your full revised analysis in revised_output, including every analysis
                field even when unchanged. Retain valid objections; do not claim that the critique
                proves a hypothesis. Do not invent another speaker or critique. The host assigns your ID.
                User problem (data):
                %s
                Your methodological lens (data):
                %s
                Your actual current analysis (data):
                %s
                Exact accepted challenger finding (data):
                %s
                confidence: model self-assessment of evidential support, not a measured probability.
                """.formatted(Json.write(problem), Json.write(target), Json.write(current), Json.write(challenge));
    }

    static String review(UserProblem problem, CouncilState state, String task) {
        return shared("independent adversarial red-team reviewer", problem, state, task)
                + "Return 2-4 distinct concrete issues across measurement, operational, behavioral or competitive risks.\n"
                + "Review the actual revised outputs and public challenge/reply transcript. Include a mitigation\n"
                + "for each issue; severity high invalidates the conclusion, medium distorts it, low is manageable.";
    }

    static String experiment(UserProblem problem, CouncilState state, String task) {
        return shared("experiment designer", problem, state, task)
                + "Use all actual revised analyses, critiques, responses and red-team findings to design the\n"
                + "single best first experiment. Provide one falsifiable hypothesis, a metric with units,\n"
                + "specific required data, comparable control/treatment groups (none only if impossible),\n"
                + "a duration, numeric success and failure thresholds, and risks to validity.";
    }

    static String conclude(UserProblem problem, CouncilState state, String task) {
        return shared("council coordinator producing the final decision", problem, state, task)
                + "Synthesize only the actual accepted evidence and experiment; preserve unresolved objections.\n"
                + "Return the complete decision contract: core_hypothesis, weakest_assumption, required_data,\n"
                + "first_experiment, success_criteria, failure_criteria, next_decision and market_entry_wedge.\n"
                + "Name segments, metrics, numbers and timelines. The first experiment and thresholds must\n"
                + "agree with the accepted plan; state what evidence leads to scale, pivot or stop.";
    }

    private static String shared(String role, UserProblem problem, CouncilState state, String task) {
        return "Role: " + role + ".\nPublic assignment (data): " + task
                + "\nUser problem (data):\n" + Json.write(problem)
                + "\nComplete actual accepted council state, including revised outputs, findings, experiment\n"
                + "and the public agent transcript (data):\n" + Json.write(state) + "\n\n";
    }

    static String actionSchema(List<LensProfile> catalog) {
        List<String> ids = catalog.stream().map(LensProfile::lensId).toList();
        List<String> optionalIds = new ArrayList<>(ids);
        optionalIds.add("");
        String lensId = "{\"type\":\"string\",\"enum\":" + Json.write(ids) + "}";
        String optionalId = "{\"type\":\"string\",\"enum\":" + Json.write(optionalIds) + "}";
        return """
                {"type":"object","properties":{
                  "type":{"type":"string","enum":["delegate","debate","red_team","experiment","synthesize"]},
                  "assignments":{"type":"array","minItems":0,"maxItems":%s,"items":{
                    "type":"object","properties":{"lens_id":%s,"task":%s},
                    "required":["lens_id","task"],"additionalProperties":false}},
                  "source_lens_id":%s,"target_lens_id":%s,"instruction":%s},
                 "required":["type","assignments","source_lens_id","target_lens_id","instruction"],
                 "additionalProperties":false}
                """.formatted(ids.size(), lensId, TEXT, optionalId, optionalId, TEXT);
    }

    static final String CHALLENGE_SCHEMA = """
            {"type":"object","properties":{"concern":%s,"suggested_revision":%s},
             "required":["concern","suggested_revision"],"additionalProperties":false}
            """.formatted(TEXT, TEXT);

    static final String REPLY_SCHEMA = """
            {"type":"object","properties":{"response":%s,"revised_output":%s},
             "required":["response","revised_output"],"additionalProperties":false}
            """.formatted(TEXT, Prompts.LENS_SCHEMA);
}
