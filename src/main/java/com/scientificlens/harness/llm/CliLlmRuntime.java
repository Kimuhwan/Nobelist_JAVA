package com.scientificlens.harness.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.scientificlens.harness.core.Json;
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
import com.scientificlens.harness.runtime.CouncilRuntime;
import com.scientificlens.harness.runtime.StageContext;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * Runs every pipeline stage through an external agent CLI ({@link CliEngine}). Unlike the local
 * llama runtime there is no grammar constraint, so the prompt asks for JSON only and the response is
 * parsed by extracting the first balanced JSON object; malformed output is retried with a stricter
 * reminder. Reuses the same stage prompts and domain drafts as {@link LocalLlmRuntime}.
 */
public final class CliLlmRuntime implements CouncilRuntime {
    private static final int MAX_ATTEMPTS = 3;

    private final CliEngine engine;
    private final LlmCouncil council;

    public CliLlmRuntime(CliEngine engine) {
        this.engine = engine;
        this.council = new LlmCouncil((system, user, schema, maxTokens, attempt, ctx) ->
                engine.complete(system, user, ctx));
    }

    @Override
    public String name() {
        return "cli:" + engine.provider().name().toLowerCase(Locale.ROOT) + ":" + engine.model();
    }

    @Override
    public CouncilAction nextAction(UserProblem problem, ProblemType type, List<LensProfile> catalog,
            CouncilState state, CouncilLimits limits, StageContext ctx) {
        return council.nextAction(problem, type, catalog, state, limits, ctx);
    }

    @Override
    public LensOutput analyzeAgent(UserProblem problem, ProblemType type, LensProfile lens, String task, StageContext ctx) {
        return council.analyzeAgent(problem, type, lens, task, ctx);
    }

    @Override
    public DebateFinding challenge(UserProblem problem, LensProfile source, LensOutput sourceOutput,
            LensOutput targetOutput, String task, StageContext ctx) {
        return council.challenge(problem, source, sourceOutput, targetOutput, task, ctx);
    }

    @Override
    public CouncilReply respond(UserProblem problem, LensProfile target, LensOutput current,
            DebateFinding challenge, StageContext ctx) {
        return council.respond(problem, target, current, challenge, ctx);
    }

    @Override
    public List<RedTeamFinding> review(UserProblem problem, CouncilState state, String task, StageContext ctx) {
        return council.review(problem, state, task, ctx);
    }

    @Override
    public ExperimentPlan experiment(UserProblem problem, CouncilState state, String task, StageContext ctx) {
        return council.experiment(problem, state, task, ctx);
    }

    @Override
    public FinalDecision conclude(UserProblem problem, CouncilState state, String task, StageContext ctx) {
        return council.conclude(problem, state, task, ctx);
    }

    @Override
    public LensOutput runLens(UserProblem problem, ProblemType type, LensProfile lens, StageContext ctx) {
        return ask(problem, Prompts.lens(problem, type, lens), Prompts.LENS_SCHEMA,
                LocalLlmRuntime.LensDraft.class, d -> new LensOutput(lens.lensId(), d.coreView(), d.hypotheses(),
                        d.assumptions(), d.evidenceNeeded(), d.counterarguments(), d.experimentSuggestions(),
                        Math.clamp(d.confidence(), 0.0, 1.0)), ctx);
    }

    @Override
    public List<DebateFinding> debate(UserProblem problem, List<LensOutput> outputs, StageContext ctx) {
        List<String> ids = outputs.stream().map(LensOutput::lensId).toList();
        Set<String> allowed = Set.copyOf(ids);
        return ask(problem, Prompts.debate(problem, outputs), Prompts.debateSchema(ids),
                LocalLlmRuntime.DebateDraft.class, d -> {
                    List<DebateFinding> valid = d.findings().stream()
                            .filter(f -> allowed.contains(f.sourceLensId()) && allowed.contains(f.targetLensId()))
                            .filter(f -> ids.size() == 1 || !f.sourceLensId().equals(f.targetLensId()))
                            .toList();
                    if (valid.isEmpty()) {
                        throw new IllegalArgumentException("no valid cross-lens debate findings");
                    }
                    return valid;
                }, ctx);
    }

    @Override
    public List<RedTeamFinding> redTeam(UserProblem problem, List<LensOutput> outputs, StageContext ctx) {
        return ask(problem, Prompts.redTeam(problem, outputs), Prompts.RED_TEAM_SCHEMA,
                LocalLlmRuntime.RedTeamDraft.class, d -> d.findings().stream()
                        .map(f -> new RedTeamFinding(f.issue(), Severity.fromValue(f.severity()), f.mitigation()))
                        .toList(), ctx);
    }

    @Override
    public ExperimentPlan designExperiment(UserProblem problem, List<LensOutput> outputs, List<DebateFinding> debate,
            List<RedTeamFinding> redTeam, StageContext ctx) {
        String debateText = String.join("\n", debate.stream()
                .map(f -> "- " + f.sourceLensId() + " -> " + f.targetLensId() + ": " + f.concern()
                        + " / " + f.suggestedRevision())
                .toList());
        return ask(problem, Prompts.experiment(problem, outputs, debateText, redTeam), Prompts.EXPERIMENT_SCHEMA,
                LocalLlmRuntime.ExperimentDraft.class, d -> new ExperimentPlan(d.hypothesis(), d.metric(),
                        d.requiredData(), noneToNull(d.controlGroup()), noneToNull(d.treatmentGroup()), d.duration(),
                        d.successCriteria(), d.failureCriteria(), d.risks()), ctx);
    }

    @Override
    public FinalDecision synthesize(UserProblem problem, List<LensOutput> outputs, ExperimentPlan plan,
            List<RedTeamFinding> redTeam, StageContext ctx) {
        return ask(problem, Prompts.synthesis(problem, outputs, Json.write(plan), redTeam), Prompts.DECISION_SCHEMA,
                FinalDecision.class, Function.identity(), ctx);
    }

    /**
     * Ask the CLI for a JSON object matching {@code schema}, parse it, and validate via {@code build}.
     * Retries with a firmer instruction on parse/validation failure since the CLI output is unconstrained.
     */
    private <D, R> R ask(UserProblem problem, String user, String schema, Class<D> draftType,
            Function<D, R> build, StageContext ctx) {
        String system = Prompts.system(problem);
        RuntimeException last = new LlmException("model output was never accepted");
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            ctx.throwIfCancelled();
            String userPrompt = Prompts.withLanguageReminder(problem, user)
                    + "\n\n반드시 아래 JSON 스키마에 맞는 JSON 객체 하나만 출력하세요. 코드펜스나 설명 문장은 절대 포함하지 마세요.\n"
                    + "JSON schema:\n" + schema
                    + (attempt > 0 ? "\n\n[재시도] 이전 출력이 유효한 JSON이 아니었습니다. 여는 중괄호로 시작해 닫는 중괄호로 끝나는 순수 JSON만 출력하세요." : "");
            String raw = engine.complete(system, userPrompt, ctx);
            ctx.throwIfCancelled();
            ctx.onToken("\n");
            String json = JsonExtract.firstObject(raw);
            if (json == null) {
                last = new LlmException("응답에서 JSON 객체를 찾지 못했습니다 (attempt " + (attempt + 1) + ")");
                ctx.onToken("[재시도: JSON 객체를 찾지 못함]\n");
                continue;
            }
            try {
                R result = build.apply(Json.MAPPER.readValue(json, draftType));
                ctx.throwIfCancelled();
                return result;
            } catch (JsonProcessingException | IllegalArgumentException | NullPointerException e) {
                last = new LlmException("스키마 검증 실패 (attempt " + (attempt + 1) + "): " + e.getMessage(), e);
                ctx.onToken("[재시도: 출력이 스키마 검증을 통과하지 못했습니다]\n");
            }
        }
        throw last;
    }

    private static String noneToNull(String value) {
        if (value == null) {
            return null;
        }
        String v = value.strip().toLowerCase(Locale.ROOT);
        return v.equals("none") || v.equals("없음") || v.equals("n/a") ? null : value;
    }

    @Override
    public void close() {
        // external process is per-call; nothing persistent to release
    }
}
