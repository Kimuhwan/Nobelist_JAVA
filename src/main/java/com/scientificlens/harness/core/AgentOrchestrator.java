package com.scientificlens.harness.core;

import com.scientificlens.harness.domain.AgentAssignment;
import com.scientificlens.harness.domain.AgentEvent;
import com.scientificlens.harness.domain.CouncilAction;
import com.scientificlens.harness.domain.CouncilLimits;
import com.scientificlens.harness.domain.CouncilReply;
import com.scientificlens.harness.domain.CouncilState;
import com.scientificlens.harness.domain.DebateFinding;
import com.scientificlens.harness.domain.ExperimentPlan;
import com.scientificlens.harness.domain.FinalDecision;
import com.scientificlens.harness.domain.HarnessResult;
import com.scientificlens.harness.domain.LensOutput;
import com.scientificlens.harness.domain.LensProfile;
import com.scientificlens.harness.domain.LensRevision;
import com.scientificlens.harness.domain.ProblemType;
import com.scientificlens.harness.domain.RedTeamFinding;
import com.scientificlens.harness.domain.UserProblem;
import com.scientificlens.harness.runtime.CouncilRuntime;
import com.scientificlens.harness.runtime.StageContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class AgentOrchestrator {
    private final List<LensProfile> catalog;

    public AgentOrchestrator(List<LensProfile> catalog) {
        this.catalog = List.copyOf(catalog);
        if (this.catalog.size() != 15 || this.catalog.stream().map(LensProfile::lensId).distinct().count() != 15) {
            throw new IllegalArgumentException("council requires the complete 15-lens catalog with unique IDs");
        }
    }

    public HarnessResult run(UserProblem problem, CouncilRuntime runtime, Orchestrator.Progress progress,
            StageContext ctx) {
        return run(problem, runtime, progress, ctx, CouncilLimits.forMode(problem.mode()));
    }

    HarnessResult run(UserProblem problem, CouncilRuntime runtime, Orchestrator.Progress progress,
            StageContext ctx, CouncilLimits limits) {
        return new Session(problem, runtime, progress, ctx, limits).run();
    }

    private final class Session {
        private final UserProblem problem;
        private final ProblemType type;
        private final CouncilRuntime runtime;
        private final Orchestrator.Progress progress;
        private final StageContext ctx;
        private final CouncilLimits limits;
        private final Map<String, LensProfile> byId = new LinkedHashMap<>();
        private final List<LensOutput> outputs = new ArrayList<>();
        private final List<DebateFinding> debates = new ArrayList<>();
        private final List<LensRevision> revisions = new ArrayList<>();
        private final List<AgentEvent> events = new ArrayList<>();
        private final Set<DebatePair> completedPairs = new HashSet<>();
        private List<AgentAssignment> assignments = List.of();
        private List<RedTeamFinding> redTeam = List.of();
        private ExperimentPlan experiment;
        private String feedback = "";
        private int rejected;
        private int step;
        private int total = 1;

        Session(UserProblem problem, CouncilRuntime runtime, Orchestrator.Progress progress,
                StageContext ctx, CouncilLimits limits) {
            this.problem = Objects.requireNonNull(problem, "problem");
            this.type = Router.classify(problem);
            this.runtime = Objects.requireNonNull(runtime, "runtime");
            this.progress = Objects.requireNonNull(progress, "progress");
            this.ctx = Objects.requireNonNull(ctx, "ctx");
            this.limits = Objects.requireNonNull(limits, "limits");
            catalog.forEach(lens -> byId.put(lens.lensId(), lens));
        }

        HarnessResult run() {
            for (int turn = 1; turn <= limits.maxManagerTurns(); turn++) {
                update("코디네이터가 다음 에이전트 호출 계획 중 (" + turn + "/" + limits.maxManagerTurns() + ")");
                ctx.throwIfCancelled();
                CouncilAction action = runtime.nextAction(problem, type, catalog, snapshot(), limits, ctx);
                ctx.throwIfCancelled();
                String error = validate(action);
                if (error != null) {
                    feedback = error;
                    emit("host", "coordinator", "rejected", error);
                    if (++rejected >= 2) {
                        throw new IllegalStateException("coordinator rejected action limit reached (2): " + error);
                    }
                    continue;
                }
                feedback = "";
                emit("coordinator", recipient(action), action.type().value(), publicInstruction(action));
                switch (action.type()) {
                    case DELEGATE -> delegate(action);
                    case DEBATE -> debate(action);
                    case RED_TEAM -> review(action);
                    case EXPERIMENT -> designExperiment(action);
                    case SYNTHESIZE -> {
                        return conclude(action);
                    }
                }
            }
            ctx.throwIfCancelled();
            throw new IllegalStateException("coordinator manager turn limit exhausted (" + limits.maxManagerTurns() + ")");
        }

        private CouncilState snapshot() {
            return new CouncilState(assignments, outputs, debates, redTeam, experiment, debates.size(), feedback, events);
        }

        private String validate(CouncilAction action) {
            if (action == null) {
                return "manager must return a typed council action";
            }
            if (action.type() != CouncilAction.Type.DELEGATE && !action.assignments().isEmpty()) {
                return "assignments must be empty except for delegate";
            }
            if (action.type() != CouncilAction.Type.DEBATE
                    && (!action.sourceLensId().isEmpty() || !action.targetLensId().isEmpty())) {
                return "source_lens_id and target_lens_id must be empty except for debate";
            }
            if (action.type() == CouncilAction.Type.DELEGATE) {
                return validateAssignments(action.assignments());
            }
            if (assignments.isEmpty() || outputs.size() != assignments.size()) {
                return "delegate all initial lens analyses before requesting " + action.type().value();
            }
            return switch (action.type()) {
                case DEBATE -> validateDebate(action);
                case RED_TEAM -> !redTeam.isEmpty() || experiment != null
                        ? "red_team may run only once, before experiment"
                        : debates.size() < limits.minDebates()
                                ? "red_team requires at least " + limits.minDebates() + " completed debates"
                                : null;
                case EXPERIMENT -> redTeam.isEmpty()
                        ? "experiment requires accepted red_team findings"
                        : experiment != null ? "experiment may run only once" : null;
                case SYNTHESIZE -> experiment == null
                        ? "synthesize requires completed debates, red_team, and experiment results" : null;
                case DELEGATE -> throw new IllegalStateException("unreachable delegate validation");
            };
        }

        private String validateAssignments(List<AgentAssignment> proposed) {
            if (!assignments.isEmpty()) {
                return "delegate may run only once as the initial batch";
            }
            if (proposed.size() < limits.minLenses() || proposed.size() > limits.maxLenses()) {
                return "delegate requires " + limits.minLenses() + " to " + limits.maxLenses() + " lens assignments";
            }
            Set<String> seen = new HashSet<>();
            for (AgentAssignment assignment : proposed) {
                if (!byId.containsKey(assignment.lensId())) {
                    return "unknown lens ID: " + assignment.lensId();
                }
                if (!seen.add(assignment.lensId())) {
                    return "duplicate lens assignment: " + assignment.lensId();
                }
            }
            return null;
        }

        private String validateDebate(CouncilAction action) {
            if (!redTeam.isEmpty() || experiment != null) {
                return "debate must occur before red_team and experiment";
            }
            if (debates.size() >= limits.maxDebates()) {
                return "debate limit reached (" + limits.maxDebates() + ")";
            }
            if (!selected(action.sourceLensId()) || !selected(action.targetLensId())) {
                return "debate source and target IDs must be selected lens IDs";
            }
            if (action.sourceLensId().equals(action.targetLensId())) {
                return "debate source and target must be distinct";
            }
            if (completedPairs.contains(new DebatePair(action.sourceLensId(), action.targetLensId()))) {
                return "duplicate ordered debate pair: " + action.sourceLensId() + " -> " + action.targetLensId();
            }
            return null;
        }

        private boolean selected(String id) {
            return assignments.stream().anyMatch(assignment -> assignment.lensId().equals(id));
        }

        private void delegate(CouncilAction action) {
            assignments = action.assignments();
            total = assignments.size() + 4;
            List<String> selectedIds = assignments.stream().map(AgentAssignment::lensId).toList();
            ctx.throwIfCancelled();
            ctx.onLensesSelected(selectedIds);
            ctx.throwIfCancelled();
            update("코디네이터가 렌즈 " + assignments.size() + "개 선택 완료");
            for (AgentAssignment assignment : assignments) {
                LensProfile lens = byId.get(assignment.lensId());
                update(lens.displayName() + " 독립 분석 중");
                emit("coordinator", lens.lensId(), "analyze", assignment.task());
                ctx.throwIfCancelled();
                LensOutput output = runtime.analyzeAgent(problem, type, lens, assignment.task(), ctx);
                ctx.throwIfCancelled();
                requireOutputId(output, lens.lensId());
                outputs.add(output);
                emit(lens.lensId(), "coordinator", "analysis", publicAnalysis(output));
                step++;
                update(lens.displayName() + " 완료");
            }
        }

        private void debate(CouncilAction action) {
            update("렌즈 간 실제 비평·응답 중 (" + (debates.size() + 1) + "/" + limits.maxDebates() + ")");
            LensOutput sourceOutput = output(action.sourceLensId());
            LensOutput targetOutput = output(action.targetLensId());
            ctx.throwIfCancelled();
            DebateFinding finding = runtime.challenge(problem, byId.get(action.sourceLensId()),
                    sourceOutput, targetOutput, action.instruction(), ctx);
            ctx.throwIfCancelled();
            if (finding == null || !finding.sourceLensId().equals(action.sourceLensId())
                    || !finding.targetLensId().equals(action.targetLensId())) {
                throw new IllegalStateException("challenge must preserve the dispatched source and target IDs");
            }
            emit(finding.sourceLensId(), finding.targetLensId(), "challenge",
                    "비평: " + finding.concern() + "\n제안한 수정: " + finding.suggestedRevision());
            ctx.throwIfCancelled();
            CouncilReply reply = runtime.respond(problem, byId.get(action.targetLensId()), targetOutput, finding, ctx);
            ctx.throwIfCancelled();
            if (reply == null) {
                throw new IllegalStateException("target agent returned no debate reply");
            }
            requireOutputId(reply.revisedOutput(), action.targetLensId());
            LensRevision revision = new LensRevision(finding, reply.response(), targetOutput, reply.revisedOutput());
            ctx.throwIfCancelled();
            int targetIndex = outputs.indexOf(targetOutput);
            outputs.set(targetIndex, reply.revisedOutput());
            debates.add(finding);
            revisions.add(revision);
            completedPairs.add(new DebatePair(action.sourceLensId(), action.targetLensId()));
            emit(action.targetLensId(), action.sourceLensId(), "reply",
                    "응답: " + reply.response() + "\n\n수정된 분석\n" + publicAnalysis(reply.revisedOutput()));
            update("비평·응답 " + debates.size() + "회 완료");
        }

        private void review(CouncilAction action) {
            step++;
            update("토론 완료 · 레드팀 비평 중");
            ctx.throwIfCancelled();
            List<RedTeamFinding> findings = runtime.review(problem, snapshot(), action.instruction(), ctx);
            ctx.throwIfCancelled();
            if (findings == null || findings.isEmpty() || findings.stream().anyMatch(Objects::isNull)) {
                throw new IllegalStateException("red_team must return nonempty accepted findings");
            }
            redTeam = List.copyOf(findings);
            emit("red_team", "coordinator", "review", String.join("\n\n", redTeam.stream()
                    .map(finding -> "위험: " + finding.issue() + "\n심각도: " + finding.severity().value()
                            + "\n완화 방안: " + finding.mitigation()).toList()));
            step++;
            update("레드팀 완료");
        }

        private void designExperiment(CouncilAction action) {
            update("실제 비평과 수정안을 반영해 실험 설계 중");
            ctx.throwIfCancelled();
            ExperimentPlan plan = runtime.experiment(problem, snapshot(), action.instruction(), ctx);
            ctx.throwIfCancelled();
            if (plan == null) {
                throw new IllegalStateException("experiment returned no accepted plan");
            }
            experiment = plan;
            emit("experiment", "coordinator", "experiment", publicExperiment(experiment));
            step++;
            update("실험 설계 완료");
        }

        private HarnessResult conclude(CouncilAction action) {
            update("코디네이터가 실제 에이전트 결과를 종합 중");
            ctx.throwIfCancelled();
            FinalDecision decision = runtime.conclude(problem, snapshot(), action.instruction(), ctx);
            ctx.throwIfCancelled();
            if (decision == null) {
                throw new IllegalStateException("coordinator returned no final decision");
            }
            emit("coordinator", "user", "final_decision", publicDecision(decision));
            HarnessResult result = new HarnessResult(problem, type, runtime.name(),
                    assignments.stream().map(AgentAssignment::lensId).toList(), outputs, debates, redTeam,
                    experiment, decision, events, revisions);
            step++;
            update("완료");
            ctx.throwIfCancelled();
            return result;
        }

        private LensOutput output(String id) {
            return outputs.stream().filter(output -> output.lensId().equals(id)).findFirst().orElseThrow();
        }

        private void requireOutputId(LensOutput output, String expectedId) {
            if (output == null || !output.lensId().equals(expectedId)) {
                throw new IllegalStateException("agent output must preserve dispatched lens ID: " + expectedId);
            }
        }

        private String recipient(CouncilAction action) {
            return switch (action.type()) {
                case DELEGATE -> "lens_agents";
                case DEBATE -> action.sourceLensId();
                case RED_TEAM -> "red_team";
                case EXPERIMENT -> "experiment";
                case SYNTHESIZE -> "coordinator";
            };
        }

        private void emit(String actor, String recipient, String action, String content) {
            ctx.throwIfCancelled();
            AgentEvent event = new AgentEvent(events.size() + 1, actor, recipient, action, content);
            events.add(event);
            ctx.onAgentEvent(event);
            ctx.throwIfCancelled();
        }

        private void update(String message) {
            ctx.throwIfCancelled();
            progress.update(step, total, message);
            ctx.throwIfCancelled();
        }
    }

    private static String publicInstruction(CouncilAction action) {
        String instruction = "공개 호출 지시: " + action.instruction();
        if (action.type() == CouncilAction.Type.DELEGATE) {
            return instruction + "\n\n" + String.join("\n", action.assignments().stream()
                    .map(assignment -> assignment.lensId() + ": " + assignment.task()).toList());
        }
        if (action.type() == CouncilAction.Type.DEBATE) {
            return instruction + "\n비평 렌즈: " + action.sourceLensId() + "\n응답 렌즈: " + action.targetLensId();
        }
        return instruction;
    }

    private static String publicAnalysis(LensOutput output) {
        return "렌즈: " + output.lensId() + "\n핵심 관점: " + output.coreView()
                + "\n" + items("가설", output.hypotheses()) + "\n" + items("가정", output.assumptions())
                + "\n" + items("필요한 근거", output.evidenceNeeded())
                + "\n" + items("반론", output.counterarguments())
                + "\n" + items("실험 제안", output.experimentSuggestions())
                + "\n모델 자기평가 확신도 (측정 확률 아님): " + output.confidence();
    }

    private static String publicExperiment(ExperimentPlan plan) {
        return "가설: " + plan.hypothesis() + "\n측정 지표: " + plan.metric()
                + "\n" + items("필요한 데이터", plan.requiredData())
                + (plan.controlGroup() == null ? "" : "\n대조군: " + plan.controlGroup())
                + (plan.treatmentGroup() == null ? "" : "\n처치군: " + plan.treatmentGroup())
                + "\n기간: " + plan.duration() + "\n성공 기준: " + plan.successCriteria()
                + "\n실패 기준: " + plan.failureCriteria() + "\n" + items("위험", plan.risks());
    }

    private static String publicDecision(FinalDecision decision) {
        return "핵심 가설: " + decision.coreHypothesis() + "\n가장 약한 가정: " + decision.weakestAssumption()
                + "\n" + items("필요한 데이터", decision.requiredData())
                + "\n첫 번째 실험: " + decision.firstExperiment() + "\n성공 기준: " + decision.successCriteria()
                + "\n실패 기준: " + decision.failureCriteria() + "\n다음 의사결정: " + decision.nextDecision()
                + "\n시장 돌파구: " + decision.marketEntryWedge();
    }

    private static String items(String label, List<String> values) {
        return label + ":\n- " + String.join("\n- ", values);
    }

    private record DebatePair(String sourceLensId, String targetLensId) {}
}
