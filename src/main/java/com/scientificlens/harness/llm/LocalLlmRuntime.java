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
import java.util.function.Predicate;

/** Runs every pipeline stage on the embedded local LLM with schema-constrained JSON output. */
public final class LocalLlmRuntime implements CouncilRuntime {
    private static final int MAX_ATTEMPTS = 3;

    // Drafts: what the model emits (lens_id etc. are filled in by code, not trusted to the model).
    record LensDraft(String coreView, List<String> hypotheses, List<String> assumptions, List<String> evidenceNeeded,
            List<String> counterarguments, List<String> experimentSuggestions, double confidence) {}

    record DebateDraft(List<DebateFinding> findings) {}

    record RedTeamItem(String issue, String severity, String mitigation) {}

    record RedTeamDraft(List<RedTeamItem> findings) {}

    record ExperimentDraft(String hypothesis, String metric, List<String> requiredData, String controlGroup,
            String treatmentGroup, String duration, String successCriteria, String failureCriteria,
            List<String> risks) {}

    private final LlamaEngine engine;
    private final LlmCouncil council;

    public LocalLlmRuntime(LlamaEngine engine) {
        this.engine = engine;
        this.council = new LlmCouncil((system, user, schema, maxTokens, attempt, ctx) ->
                engine.completeJson(system, user, schema, maxTokens, attempt, partial -> false, ctx));
    }

    @Override
    public String name() {
        return "local-llm:" + engine.settings().modelPath().getFileName();
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
        return ask(problem, Prompts.lens(problem, type, lens), Prompts.LENS_SCHEMA, 900, LensDraft.class, d ->
                new LensOutput(lens.lensId(), d.coreView(), d.hypotheses(), d.assumptions(), d.evidenceNeeded(),
                        d.counterarguments(), d.experimentSuggestions(),
                        Math.clamp(d.confidence(), 0.0, 1.0)), ctx);
    }

    @Override
    public List<DebateFinding> debate(UserProblem problem, List<LensOutput> outputs, StageContext ctx) {
        List<String> ids = outputs.stream().map(LensOutput::lensId).toList();
        Set<String> allowed = Set.copyOf(ids);
        return ask(problem, Prompts.debate(problem, outputs), Prompts.debateSchema(ids), 900, DebateDraft.class, d -> {
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
        return ask(problem, Prompts.redTeam(problem, outputs), Prompts.RED_TEAM_SCHEMA, 800, RedTeamDraft.class, d ->
                d.findings().stream()
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
        return ask(problem, Prompts.experiment(problem, outputs, debateText, redTeam), Prompts.EXPERIMENT_SCHEMA, 900,
                ExperimentDraft.class, d -> new ExperimentPlan(d.hypothesis(), d.metric(), d.requiredData(),
                        noneToNull(d.controlGroup()), noneToNull(d.treatmentGroup()), d.duration(),
                        d.successCriteria(), d.failureCriteria(), d.risks()), ctx);
    }

    @Override
    public FinalDecision synthesize(UserProblem problem, List<LensOutput> outputs, ExperimentPlan plan,
            List<RedTeamFinding> redTeam, StageContext ctx) {
        return ask(problem, Prompts.synthesis(problem, outputs, Json.write(plan), redTeam), Prompts.DECISION_SCHEMA,
                900, FinalDecision.class, Function.identity(), ctx);
    }

    /**
     * Generate, parse, validate; retry with a different seed if the output is truncated or fails domain
     * validation. Grammar-constrained decoding makes the JSON shape correct in almost all cases.
     */
    private <D, R> R ask(UserProblem problem, String user, String schema, int maxTokens, Class<D> draftType,
            Function<D, R> build, StageContext ctx) {
        String system = Prompts.system(problem);
        String prompt = Prompts.withLanguageReminder(problem, user);
        RuntimeException last = new LlmException("model output was never accepted");
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            ctx.throwIfCancelled();
            boolean lastAttempt = attempt == MAX_ATTEMPTS - 1;
            // Small models sometimes drift into English; detect it within the first ~160 chars and resample.
            Predicate<String> wrongLanguage = partial -> !lastAttempt && Prompts.expectsKorean(problem)
                    && Prompts.hangulRatio(Prompts.stringValues(partial)) < 0.3;
            String raw = engine.completeJson(system, prompt, schema, maxTokens, attempt, wrongLanguage, ctx);
            ctx.throwIfCancelled();
            ctx.onToken("\n");
            if (raw == null) {
                ctx.onToken("[재시도: 한국어가 아닌 출력 감지]\n");
                continue;
            }
            try {
                R result = build.apply(Json.MAPPER.readValue(raw, draftType));
                ctx.throwIfCancelled();
                return result;
            } catch (JsonProcessingException | IllegalArgumentException | NullPointerException e) {
                last = new LlmException("invalid model output (attempt " + (attempt + 1) + "): " + e.getMessage(), e);
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
        engine.close();
    }
}
