package com.scientificlens.harness.llm;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.scientificlens.harness.core.Json;
import com.scientificlens.harness.domain.AgentAssignment;
import com.scientificlens.harness.domain.Checks;
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
import com.scientificlens.harness.domain.Severity;
import com.scientificlens.harness.domain.UserProblem;
import com.scientificlens.harness.runtime.StageContext;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

final class LlmCouncil {
    private static final int MAX_ATTEMPTS = 3;

    @FunctionalInterface
    interface Generator {
        String generate(String system, String user, String schema, int maxTokens, int attempt, StageContext ctx);
    }

    private record ActionDraft(String type, List<AgentAssignment> assignments, String sourceLensId,
            String targetLensId, String instruction) {}

    private record ChallengeDraft(String concern, String suggestedRevision) {}

    private record ReplyDraft(String response, LocalLlmRuntime.LensDraft revisedOutput) {}

    private final Generator generator;

    LlmCouncil(Generator generator) {
        this.generator = Objects.requireNonNull(generator, "generator");
    }

    CouncilAction nextAction(UserProblem problem, ProblemType type, List<LensProfile> catalog,
            CouncilState state, CouncilLimits limits, StageContext ctx) {
        return ask(problem, CouncilPrompts.nextAction(problem, type, catalog, state, limits),
                CouncilPrompts.actionSchema(catalog), 1600, ActionDraft.class, LlmCouncil::action, ctx);
    }

    LensOutput analyzeAgent(UserProblem problem, ProblemType type, LensProfile lens, String task, StageContext ctx) {
        return ask(problem, CouncilPrompts.analyze(problem, type, lens, task), Prompts.LENS_SCHEMA,
                1200, LocalLlmRuntime.LensDraft.class, draft -> output(lens.lensId(), draft), ctx);
    }

    DebateFinding challenge(UserProblem problem, LensProfile source, LensOutput sourceOutput,
            LensOutput targetOutput, String task, StageContext ctx) {
        requireId(source.lensId(), sourceOutput.lensId(), "source analysis");
        if (source.lensId().equals(targetOutput.lensId())) {
            throw new IllegalArgumentException("source and target lens IDs must differ");
        }
        return ask(problem, CouncilPrompts.challenge(problem, source, sourceOutput, targetOutput, task),
                CouncilPrompts.CHALLENGE_SCHEMA, 700, ChallengeDraft.class,
                draft -> new DebateFinding(source.lensId(), targetOutput.lensId(),
                        draft.concern(), draft.suggestedRevision()), ctx);
    }

    CouncilReply respond(UserProblem problem, LensProfile target, LensOutput current,
            DebateFinding challenge, StageContext ctx) {
        requireId(target.lensId(), current.lensId(), "current analysis");
        requireId(target.lensId(), challenge.targetLensId(), "challenge target");
        return ask(problem, CouncilPrompts.respond(problem, target, current, challenge),
                CouncilPrompts.REPLY_SCHEMA, 1600, ReplyDraft.class,
                draft -> new CouncilReply(draft.response(), output(target.lensId(), draft.revisedOutput())), ctx);
    }

    List<RedTeamFinding> review(UserProblem problem, CouncilState state, String task, StageContext ctx) {
        return ask(problem, CouncilPrompts.review(problem, state, task), Prompts.RED_TEAM_SCHEMA,
                1100, LocalLlmRuntime.RedTeamDraft.class,
                draft -> Checks.nonEmpty(draft.findings().stream()
                        .map(f -> new RedTeamFinding(f.issue(), Severity.fromValue(f.severity()), f.mitigation()))
                        .toList(), "red_team findings"), ctx);
    }

    ExperimentPlan experiment(UserProblem problem, CouncilState state, String task, StageContext ctx) {
        return ask(problem, CouncilPrompts.experiment(problem, state, task), Prompts.EXPERIMENT_SCHEMA,
                1400, LocalLlmRuntime.ExperimentDraft.class,
                draft -> new ExperimentPlan(draft.hypothesis(), draft.metric(), draft.requiredData(),
                        noneToNull(draft.controlGroup()), noneToNull(draft.treatmentGroup()), draft.duration(),
                        draft.successCriteria(), draft.failureCriteria(), draft.risks()), ctx);
    }

    FinalDecision conclude(UserProblem problem, CouncilState state, String task, StageContext ctx) {
        return ask(problem, CouncilPrompts.conclude(problem, state, task), Prompts.DECISION_SCHEMA,
                1400, FinalDecision.class, Function.identity(), ctx);
    }

    private static CouncilAction action(ActionDraft draft) {
        CouncilAction.Type type = switch (draft.type()) {
            case "delegate" -> CouncilAction.Type.DELEGATE;
            case "debate" -> CouncilAction.Type.DEBATE;
            case "red_team" -> CouncilAction.Type.RED_TEAM;
            case "experiment" -> CouncilAction.Type.EXPERIMENT;
            case "synthesize" -> CouncilAction.Type.SYNTHESIZE;
            default -> throw new IllegalArgumentException("unknown council action: " + draft.type());
        };
        if (type != CouncilAction.Type.DELEGATE && !draft.assignments().isEmpty()) {
            throw new IllegalArgumentException("assignments must be [] for " + draft.type());
        }
        if (type != CouncilAction.Type.DEBATE
                && (!draft.sourceLensId().isEmpty() || !draft.targetLensId().isEmpty())) {
            throw new IllegalArgumentException("source_lens_id and target_lens_id must be empty strings for "
                    + draft.type());
        }
        return new CouncilAction(type, draft.assignments(), draft.sourceLensId(), draft.targetLensId(),
                draft.instruction());
    }

    private static LensOutput output(String id, LocalLlmRuntime.LensDraft draft) {
        return new LensOutput(id, draft.coreView(), draft.hypotheses(), draft.assumptions(), draft.evidenceNeeded(),
                draft.counterarguments(), draft.experimentSuggestions(), draft.confidence());
    }

    private static void requireId(String expected, String actual, String field) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(field + " lens ID must be " + expected);
        }
    }

    private <D, R> R ask(UserProblem problem, String user, String schema, int maxTokens, Class<D> draftType,
            Function<D, R> build, StageContext ctx) {
        JsonNode schemaNode;
        try {
            schemaNode = Json.MAPPER.readTree(schema);
        } catch (JsonProcessingException e) {
            throw new LlmException("invalid council tool schema", e);
        }
        String prompt = Prompts.withLanguageReminder(problem, user)
                + "\nReturn exactly one JSON object matching this schema, with no extra keys, code fences or prose.\n"
                + "JSON schema:\n" + schema
                + "\n\nFit the complete JSON result within approximately " + maxTokens + " output tokens, including keys.\n"
                + "Use short concrete sentences and only the necessary list items within the schema bounds.\n"
                + "Keep all required fields and complete every value and closing brace; use concise wording so the full object fits.\n";
        String system = Prompts.system(problem)
                + "\nThis is a self-contained inference request. Work only from the supplied question, lens and accepted state.\n"
                + "Do not invoke tools, inspect files, access the network or spawn extra agents.\n"
                + "The host alone executes selected typed council actions. Return your assigned JSON result directly.\n"
                + "Express missing evidence in the appropriate result fields without performing external research.\n";
        String feedback = "";
        RuntimeException last = new LlmException("model output was never accepted");
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            ctx.throwIfCancelled();
            String raw = generator.generate(system, prompt + feedback, schema,
                    maxTokens, attempt, ctx);
            ctx.throwIfCancelled();
            ctx.onToken("\n");
            R result;
            try {
                if (raw == null) {
                    throw new IllegalArgumentException("generation stopped by the output language guard; use the requested language");
                }
                String json = JsonExtract.firstObject(raw);
                if (json == null) {
                    throw new IllegalArgumentException("response contains no complete JSON object");
                }
                JsonNode node = Json.MAPPER.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(json);
                validate(node, schemaNode, "$", 0);
                result = build.apply(Json.MAPPER.treeToValue(node, draftType));
            } catch (JsonProcessingException | IllegalArgumentException | NullPointerException e) {
                String detail = validationMessage(e);
                last = new LlmException("invalid council output (attempt " + (attempt + 1) + "): " + detail, e);
                feedback = "\n\nCorrection required for the previous result: " + detail
                        + ". Return a complete replacement object matching the schema.\n";
                if (attempt + 1 < MAX_ATTEMPTS) {
                    ctx.onToken("[재시도: " + detail + "]\n");
                }
                continue;
            }
            ctx.throwIfCancelled();
            return result;
        }
        ctx.throwIfCancelled();
        throw last;
    }

    private static String validationMessage(Exception error) {
        if (error instanceof JsonProcessingException jsonError) {
            return jsonError.getOriginalMessage();
        }
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static void validate(JsonNode value, JsonNode schema, String path, int depth) {
        if (depth > 12) {
            throw new IllegalArgumentException(path + " exceeds the allowed JSON nesting");
        }
        String type = schema.path("type").asText();
        switch (type) {
            case "object" -> {
                if (!value.isObject()) {
                    throw new IllegalArgumentException(path + " must be an object");
                }
                for (JsonNode required : schema.path("required")) {
                    if (!value.has(required.asText())) {
                        throw new IllegalArgumentException(path + "." + required.asText() + " is required");
                    }
                }
                JsonNode properties = schema.path("properties");
                value.properties().forEach(field -> {
                    JsonNode fieldSchema = properties.get(field.getKey());
                    if (fieldSchema == null) {
                        throw new IllegalArgumentException(path + "." + field.getKey() + " is not an allowed field");
                    }
                    validate(field.getValue(), fieldSchema, path + "." + field.getKey(), depth + 1);
                });
            }
            case "array" -> {
                if (!value.isArray()) {
                    throw new IllegalArgumentException(path + " must be an array");
                }
                if (value.size() < schema.path("minItems").asInt(0)
                        || value.size() > schema.path("maxItems").asInt(Integer.MAX_VALUE)) {
                    throw new IllegalArgumentException(path + " has invalid item count " + value.size());
                }
                for (int index = 0; index < value.size(); index++) {
                    validate(value.get(index), schema.path("items"), path + "[" + index + "]", depth + 1);
                }
            }
            case "string" -> {
                if (!value.isTextual()) {
                    throw new IllegalArgumentException(path + " must be a string");
                }
                if (value.textValue().strip().length() < schema.path("minLength").asInt(0)) {
                    throw new IllegalArgumentException(path + " must not be blank");
                }
            }
            case "number" -> {
                if (!value.isNumber() || !Double.isFinite(value.doubleValue())) {
                    throw new IllegalArgumentException(path + " must be a finite number");
                }
                if (schema.has("minimum") && value.doubleValue() < schema.get("minimum").doubleValue()
                        || schema.has("maximum") && value.doubleValue() > schema.get("maximum").doubleValue()) {
                    throw new IllegalArgumentException(path + " is outside the allowed numeric range");
                }
            }
            default -> throw new IllegalArgumentException("unsupported schema type " + type);
        }
        if (schema.has("enum")) {
            boolean allowed = false;
            for (JsonNode choice : schema.get("enum")) {
                if (choice.equals(value)) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) {
                throw new IllegalArgumentException(path + " must match an allowed enum value");
            }
        }
    }

    private static String noneToNull(String value) {
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        return normalized.equals("none") || normalized.equals("없음") || normalized.equals("n/a") ? null : value;
    }
}
