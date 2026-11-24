package com.scientificlens.harness.core;

import com.scientificlens.harness.domain.DebateFinding;
import com.scientificlens.harness.domain.ExperimentPlan;
import com.scientificlens.harness.domain.FinalDecision;
import com.scientificlens.harness.domain.HarnessResult;
import com.scientificlens.harness.domain.LensOutput;
import com.scientificlens.harness.domain.LensProfile;
import com.scientificlens.harness.domain.ProblemType;
import com.scientificlens.harness.domain.RedTeamFinding;
import com.scientificlens.harness.domain.UserProblem;
import com.scientificlens.harness.runtime.HarnessRuntime;
import com.scientificlens.harness.runtime.CouncilRuntime;
import com.scientificlens.harness.runtime.MockRuntime;
import com.scientificlens.harness.runtime.StageContext;
import java.util.ArrayList;
import java.util.List;

/**
 * 질문을 관점별로 분석하고 반론, 비평, 실험 계획과 최종 결정을 하나의 보고서로 구성합니다.
 * 실제 모델의 역할 간 토론은 {@link AgentOrchestrator}에 위임합니다.
 */
public final class Orchestrator {
    /** Progress callback: {@code step} of {@code total} finished stages, plus a human-readable message. */
    @FunctionalInterface
    public interface Progress {
        Progress NONE = (step, total, message) -> {};

        void update(int step, int total, String message);
    }

    private final List<LensProfile> profiles;

    /** 앱에 포함된 분석 관점 목록을 사용합니다. */
    public Orchestrator() {
        this(LensRegistry.load());
    }

    /**
     * 분석에 사용할 관점 목록을 지정합니다.
     * @param profiles 사용 가능한 관점의 프로필
     */
    public Orchestrator(List<LensProfile> profiles) {
        this.profiles = List.copyOf(profiles);
    }

    /**
     * 모델 연결 없이 같은 입력에 대해 재현 가능한 분석을 실행합니다.
     * @param problem 질문과 분석 모드
     * @return 테스트용 Mock 엔진이 작성한 분석 결과
     */
    public static HarnessResult analyzeMock(UserProblem problem) {
        return new Orchestrator().run(problem, new MockRuntime(), Progress.NONE, StageContext.NONE);
    }

    /**
     * 선택한 엔진으로 질문을 분석하고 완료된 결과를 반환합니다.
     * @param problem 질문과 분석 모드
     * @param runtime 관점 분석과 실험 설계를 수행할 엔진
     * @param progress 현재 단계와 진행 메시지를 받을 콜백
     * @param ctx 취소 여부와 엔진의 공개 실행 기록을 전달하는 컨텍스트
     * @return 관점별 분석, 반론, 실험 계획과 결정을 담은 보고서
     * @throws java.util.concurrent.CancellationException 작업이 취소된 경우
     */
    public HarnessResult run(UserProblem problem, HarnessRuntime runtime, Progress progress, StageContext ctx) {
        if (runtime instanceof CouncilRuntime council) {
            return new AgentOrchestrator(profiles).run(problem, council, progress, ctx);
        }
        ProblemType type = Router.classify(problem);
        List<LensProfile> selected = Router.select(problem, profiles);
        int total = selected.size() + 4;
        int step = 0;
        progress.update(step, total, "문제 유형: " + type.value() + ", 렌즈 " + selected.size() + "개 선택");

        List<LensOutput> outputs = new ArrayList<>();
        for (LensProfile lens : selected) {
            ctx.throwIfCancelled();
            progress.update(step, total, lens.displayName() + " 분석 중");
            outputs.add(runtime.runLens(problem, type, lens, ctx));
            progress.update(++step, total, lens.displayName() + " 완료");
        }

        ctx.throwIfCancelled();
        progress.update(step, total, "렌즈 간 토론 중");
        List<DebateFinding> debate = runtime.debate(problem, outputs, ctx);
        progress.update(++step, total, "토론 완료");

        ctx.throwIfCancelled();
        progress.update(step, total, "레드팀 비평 중");
        List<RedTeamFinding> redTeam = runtime.redTeam(problem, outputs, ctx);
        progress.update(++step, total, "레드팀 완료");

        ctx.throwIfCancelled();
        progress.update(step, total, "실험 설계 중");
        ExperimentPlan plan = runtime.designExperiment(problem, outputs, debate, redTeam, ctx);
        progress.update(++step, total, "실험 설계 완료");

        ctx.throwIfCancelled();
        progress.update(step, total, "최종 결정 종합 중");
        FinalDecision decision = runtime.synthesize(problem, outputs, plan, redTeam, ctx);
        progress.update(++step, total, "완료");

        return new HarnessResult(
                problem,
                type,
                runtime.name(),
                selected.stream().map(LensProfile::lensId).toList(),
                outputs,
                debate,
                redTeam,
                plan,
                decision);
    }
}
