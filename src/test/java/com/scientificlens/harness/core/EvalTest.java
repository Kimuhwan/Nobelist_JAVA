package com.scientificlens.harness.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.scientificlens.harness.domain.Mode;
import com.scientificlens.harness.domain.UserProblem;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

/** Port of evals/run_local.py: every case in evals/cases.jsonl must pass the grader in mock mode. */
class EvalTest {
    @Test
    void allLocalEvalCasesPass() throws Exception {
        List<String> lines = Files.readAllLines(Path.of("evals", "cases.jsonl"), StandardCharsets.UTF_8).stream()
                .filter(l -> !l.isBlank())
                .toList();
        assertEquals(5, lines.size());
        for (String line : lines) {
            JsonNode c = Json.MAPPER.readTree(line);
            var problem = new UserProblem(c.get("input").asText(), Mode.fromValue(c.get("mode").asText()));
            List<String> mustInclude = StreamSupport.stream(c.get("must_include").spliterator(), false)
                    .map(JsonNode::asText)
                    .toList();
            var grade = Grader.grade(c.get("id").asText(), Orchestrator.analyzeMock(problem), mustInclude);
            assertTrue(grade.passed(), grade.caseId() + ": " + grade.failures());
        }
    }
}
