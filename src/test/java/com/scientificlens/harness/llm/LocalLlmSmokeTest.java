package com.scientificlens.harness.llm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.scientificlens.harness.core.Grader;
import com.scientificlens.harness.core.Orchestrator;
import com.scientificlens.harness.core.Reporting;
import com.scientificlens.harness.domain.HarnessResult;
import com.scientificlens.harness.domain.Mode;
import com.scientificlens.harness.domain.UserProblem;
import com.scientificlens.harness.runtime.StageContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Real inference with the embedded model. Opt-in (slow):
 * {@code mvnw test -Dtest=LocalLlmSmokeTest -Dslh.model=C:\path\model.gguf}
 */
class LocalLlmSmokeTest {
    @Test
    void fastModeProducesValidContract() throws Exception {
        String model = System.getProperty("slh.model", "");
        assumeTrue(!model.isBlank() && !model.startsWith("${") && Files.isRegularFile(Path.of(model)),
                "set -Dslh.model=<gguf> to run");

        LlmSettings settings = LlmSettings.defaults().withModelPath(Path.of(model));
        try (LocalLlmRuntime runtime = new LocalLlmRuntime(LlamaEngine.load(settings))) {
            long start = System.nanoTime();
            HarnessResult result = new Orchestrator().run(
                    new UserProblem("AI로 농산물 폐기량을 줄이는 서비스를 만들 수 있을까?", Mode.FAST),
                    runtime,
                    (step, total, msg) -> System.out.printf("[%d/%d] %s (%.0fs)%n", step, total, msg,
                            (System.nanoTime() - start) / 1e9),
                    StageContext.NONE);
            System.out.println(Reporting.markdown(result));
            Files.writeString(Path.of("target", "llm-smoke-report.md"), Reporting.markdown(result));

            var grade = Grader.grade("llm_smoke", result, List.of("core_hypothesis", "weakest_assumption",
                    "required_data", "first_experiment", "success_criteria", "failure_criteria", "next_decision"));
            assertTrue(grade.passed(), grade.failures().toString());
            assertFalse(result.debateFindings().isEmpty());
            assertTrue(result.runtime().startsWith("local-llm"));
        }
    }
}
