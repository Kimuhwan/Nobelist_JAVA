package com.scientificlens.harness.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.scientificlens.harness.core.Json;
import com.scientificlens.harness.core.LensRegistry;
import com.scientificlens.harness.core.Router;
import com.scientificlens.harness.domain.AgentAssignment;
import com.scientificlens.harness.domain.AgentEvent;
import com.scientificlens.harness.domain.CouncilAction;
import com.scientificlens.harness.domain.CouncilLimits;
import com.scientificlens.harness.domain.CouncilState;
import com.scientificlens.harness.domain.DebateFinding;
import com.scientificlens.harness.domain.ExperimentPlan;
import com.scientificlens.harness.domain.FinalDecision;
import com.scientificlens.harness.domain.LensOutput;
import com.scientificlens.harness.domain.Mode;
import com.scientificlens.harness.domain.RedTeamFinding;
import com.scientificlens.harness.domain.Severity;
import com.scientificlens.harness.domain.UserProblem;
import com.scientificlens.harness.runtime.StageContext;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LlmCouncilTest {
    private final UserProblem problem = new UserProblem("Can a school pilot reduce food waste?", Mode.FAST);
    private final CouncilLimits limits = CouncilLimits.forMode(Mode.FAST);

    @Test
    void managerSelectsFromRealCatalogAndEachActorMakesAnIndependentRequest() {
        var catalog = LensRegistry.load();
        var source = catalog.getFirst();
        var target = catalog.get(1);
        var assignments = List.of(new AgentAssignment(source.lensId(), "Test the measurement mechanism"),
                new AgentAssignment(target.lensId(), "Test adoption in the school pilot"),
                new AgentAssignment(catalog.get(2).lensId(), "Test the weakest causal assumption"),
                new AgentAssignment(catalog.get(3).lensId(), "Test resource requirements"),
                new AgentAssignment(catalog.get(4).lensId(), "Test an unexpected failure mode"));
        var dispatch = new CouncilAction(CouncilAction.Type.DELEGATE, assignments, "", "", "Analyze independently");
        var fake = new ScriptedGenerator(Json.write(dispatch), Json.write(draft("source-result")),
                Json.write(draft("target-current")),
                Json.write(Map.of("concern", "Attendance confounds the target metric",
                        "suggested_revision", "Compare waste per attending student with a matched baseline")),
                Json.write(Map.of("response", "Accepted: normalize by attendance and retain selection risk",
                        "revised_output", draft("target-revised"))));
        var council = new LlmCouncil(fake);

        var selected = council.nextAction(problem, Router.classify(problem), catalog, emptyState(), limits, StageContext.NONE);
        var sourceOutput = council.analyzeAgent(problem, Router.classify(problem), source,
                selected.assignments().getFirst().task(), StageContext.NONE);
        var currentTarget = council.analyzeAgent(problem, Router.classify(problem), target,
                selected.assignments().get(1).task(), StageContext.NONE);
        var finding = council.challenge(problem, source, sourceOutput, currentTarget,
                "Challenge confounding in the primary metric", StageContext.NONE);
        var reply = council.respond(problem, target, currentTarget, finding, StageContext.NONE);

        assertEquals(dispatch, selected);
        assertEquals(15, catalog.size());
        assertTrue(fake.calls.getFirst().user().contains(Json.write(catalog)));
        assertTrue(fake.calls.getFirst().user().contains(Json.write(limits)));
        assertTrue(fake.calls.getFirst().user().contains("Choose only a currently permitted tool: [delegate]"));
        assertEquals(5, fake.calls.size());
        var sourceCall = fake.calls.get(1);
        var targetCall = fake.calls.get(2);
        assertTrue(sourceCall.user().contains(assignments.getFirst().task()));
        assertTrue(sourceCall.user().contains(Json.write(source)));
        assertFalse(sourceCall.user().contains(Json.write(target)));
        assertTrue(targetCall.user().contains(assignments.get(1).task()));
        assertTrue(targetCall.user().contains(Json.write(target)));
        assertFalse(targetCall.user().contains("source-result"));
        assertEquals(source.lensId(), sourceOutput.lensId());
        assertEquals(target.lensId(), currentTarget.lensId());
        assertEquals(source.lensId(), finding.sourceLensId());
        assertEquals(target.lensId(), finding.targetLensId());
        assertTrue(fake.calls.get(3).user().contains(Json.write(sourceOutput)));
        assertTrue(fake.calls.get(3).user().contains(Json.write(currentTarget)));
        assertTrue(fake.calls.get(3).user().contains("Role: source lens challenger"));
        assertTrue(fake.calls.get(4).user().contains("Role: target lens responder"));
        assertTrue(fake.calls.get(4).user().contains(Json.write(finding)));
        assertTrue(fake.calls.get(4).user().contains(Json.write(currentTarget)));
        assertEquals("Accepted: normalize by attendance and retain selection risk", reply.response());
        assertEquals(target.lensId(), reply.revisedOutput().lensId());
        assertEquals("target-revised view", reply.revisedOutput().coreView());
        assertEquals(List.of("target-revised hypothesis"), reply.revisedOutput().hypotheses());
        assertTrue(fake.calls.stream().allMatch(call -> call.attempt() == 0 && call.maxTokens() > 0));
        assertTrue(sourceCall.user().contains("This assignment is self-contained"));
        assertTrue(fake.calls.stream().allMatch(call -> call.system().contains(
                "Do not invoke tools, inspect files, access the network or spawn extra agents")));
        assertTrue(fake.calls.stream().allMatch(call -> call.user().contains(
                "approximately " + call.maxTokens() + " output tokens") && call.user().contains("Keep all required fields")));
        assertTrue(sourceCall.system().contains("Never speak as the scientist"));
        assertTrue(sourceCall.system().contains("never expose private reasoning"));
        assertTrue(sourceCall.user().contains("not a measured probability"));
    }

    @Test
    void managerAndDownstreamToolsReceiveCompleteActualRevisedStateAndTranscript() {
        var source = LensRegistry.load().getFirst();
        var target = LensRegistry.load().get(1);
        var finding = new DebateFinding(source.lensId(), target.lensId(), "actual-critique", "actual-revision-request");
        var assignments = List.of(new AgentAssignment(source.lensId(), "source-task"),
                new AgentAssignment(target.lensId(), "target-task"));
        var outputs = List.of(output(source.lensId(), "source-actual"), output(target.lensId(), "target-revised-actual"));
        var events = List.of(new AgentEvent(1, source.lensId(), target.lensId(), "challenge", "actual-public-critique"),
                new AgentEvent(2, target.lensId(), source.lensId(), "reply", "actual-public-reply"));
        var state = new CouncilState(assignments, outputs, List.of(finding), List.of(), null, 1,
                "Previous action rejected: do not repeat this ordered pair", events);
        var red = new RedTeamFinding("Actual selection bias", Severity.HIGH, "Sample all attending students");
        var plan = plan();
        var decision = decision();
        var fake = new ScriptedGenerator(
                Json.write(new CouncilAction(CouncilAction.Type.RED_TEAM, List.of(), "", "", "Review the actual critique")),
                Json.write(Map.of("findings", List.of(red))), Json.write(plan), Json.write(decision));
        var council = new LlmCouncil(fake);

        var action = council.nextAction(problem, Router.classify(problem), LensRegistry.load(), state, limits, StageContext.NONE);
        var findings = council.review(problem, state, action.instruction(), StageContext.NONE);
        var reviewedState = new CouncilState(assignments, outputs, List.of(finding), findings, null, 1, "", events);
        var experiment = council.experiment(problem, reviewedState, "Resolve the red-team measurement risk", StageContext.NONE);
        var finalState = new CouncilState(assignments, outputs, List.of(finding), findings, experiment, 1, "", events);
        var result = council.conclude(problem, finalState, "Conclude from the accepted experiment", StageContext.NONE);

        assertEquals(CouncilAction.Type.RED_TEAM, action.type());
        assertTrue(fake.calls.getFirst().user().contains(Json.write(state)));
        assertTrue(fake.calls.getFirst().user().contains(Json.write(LensRegistry.load())));
        assertTrue(fake.calls.getFirst().user().contains("Choose only a currently permitted tool: [debate, red_team]"));
        assertTrue(fake.calls.get(1).user().contains(Json.write(state)));
        assertTrue(fake.calls.get(2).user().contains(Json.write(reviewedState)));
        assertTrue(fake.calls.get(3).user().contains(Json.write(finalState)));
        assertTrue(fake.calls.stream().allMatch(call -> call.user().contains("target-revised-actual")
                && call.user().contains("actual-public-reply") && call.user().contains("actual-critique")));
        assertEquals(List.of(red), findings);
        assertEquals(plan, experiment);
        assertEquals(decision, result);
        assertTrue(CouncilPrompts.nextAction(problem, Router.classify(problem), LensRegistry.load(), reviewedState, limits)
                .contains("Choose only a currently permitted tool: [experiment]"));
        assertTrue(CouncilPrompts.nextAction(problem, Router.classify(problem), LensRegistry.load(), finalState, limits)
                .contains("Choose only a currently permitted tool: [synthesize]"));
    }

    @Test
    void parsingRetriesAreBoundedAndCarryTheConcreteValidationFailure() {
        var fake = new ScriptedGenerator("{}", Json.write(draft(" ")).replace(" view", ""), Json.write(draft("accepted")));
        var council = new LlmCouncil(fake);
        var result = council.analyzeAgent(problem, Router.classify(problem), LensRegistry.load().getFirst(), "Analyze", StageContext.NONE);

        assertEquals("accepted view", result.coreView());
        assertEquals(List.of(0, 1, 2), fake.calls.stream().map(Call::attempt).toList());
        assertTrue(fake.calls.get(1).user().contains("$.core_view is required"));
        assertTrue(fake.calls.get(2).user().contains("$.core_view must not be blank"));
    }

    @Test
    void threeMalformedResultsFailVisiblyWithoutAFourthRequest() {
        var fake = new ScriptedGenerator("no object", "{unclosed", "{}");
        var error = assertThrows(LlmException.class, () -> new LlmCouncil(fake).analyzeAgent(problem,
                Router.classify(problem), LensRegistry.load().getFirst(), "Analyze", StageContext.NONE));

        assertEquals(3, fake.calls.size());
        assertTrue(error.getMessage().contains("attempt 3"));
        assertTrue(error.getMessage().contains("core_view is required"));
        assertTrue(fake.calls.get(1).user().contains("no complete JSON object"));
    }

    @Test
    void actionSchemaRejectsUnknownEnumAndIrrelevantIdsInsteadOfNormalizingThem() {
        String valid = Json.write(new CouncilAction(CouncilAction.Type.RED_TEAM, List.of(), "", "", "Review risks"));
        String badId = Json.write(new CouncilAction(CouncilAction.Type.RED_TEAM, List.of(),
                LensRegistry.load().getFirst().lensId(), "", "Review risks"));
        var fake = new ScriptedGenerator(valid.replace("red_team", "RED_TEAM"), badId, valid);
        var action = new LlmCouncil(fake).nextAction(problem, Router.classify(problem), LensRegistry.load(),
                emptyState(), limits, StageContext.NONE);

        assertEquals(CouncilAction.Type.RED_TEAM, action.type());
        assertEquals("", action.sourceLensId());
        assertEquals(List.of(), action.assignments());
        assertTrue(fake.calls.get(1).user().contains("$.type must match an allowed enum value"));
        assertTrue(fake.calls.get(2).user().contains("source_lens_id and target_lens_id must be empty strings"));
    }

    @Test
    void schemasRejectExtraFieldsDuplicateKeysWrongTypesAndInvalidConfidence() {
        String valid = Json.write(draft("valid"));
        List<String> invalid = List.of(
                valid.replaceFirst("\\{", "{\"lens_id\":\"forged\","),
                valid.replaceFirst("\\{", "{\"core_view\":\"duplicate\","),
                valid.replace("0.7", "\"0.7\""),
                valid.replace("0.7", "1.7"),
                valid.replace("\"valid hypothesis\"", ""));
        for (String raw : invalid) {
            var fake = new ScriptedGenerator(raw, raw, raw);
            assertThrows(LlmException.class, () -> new LlmCouncil(fake).analyzeAgent(problem,
                    Router.classify(problem), LensRegistry.load().getFirst(), "Analyze", StageContext.NONE));
            assertEquals(3, fake.calls.size());
        }
    }

    @Test
    void redTeamMustReturnAtLeastOneFinding() {
        var fake = new ScriptedGenerator("{\"findings\":[]}", "{\"findings\":[]}", "{\"findings\":[]}");
        var error = assertThrows(LlmException.class,
                () -> new LlmCouncil(fake).review(problem, emptyState(), "Review", StageContext.NONE));
        assertTrue(error.getMessage().contains("findings has invalid item count 0"));
        assertEquals(3, fake.calls.size());
    }

    @Test
    void transportFailuresPropagateImmediatelyWithoutParseRetries() {
        var failure = new LlmException("provider process failed");
        var calls = new AtomicInteger();
        var council = new LlmCouncil((system, user, schema, maxTokens, attempt, ctx) -> {
            calls.incrementAndGet();
            throw failure;
        });

        assertSame(failure, assertThrows(LlmException.class, () -> council.analyzeAgent(problem,
                Router.classify(problem), LensRegistry.load().getFirst(), "Analyze", StageContext.NONE)));
        assertEquals(1, calls.get());
    }

    @Test
    void cancellationBeforeOrImmediatelyAfterGenerationPreventsAcceptedResultsAndRetries() {
        var context = new MutableContext();
        context.cancelled = true;
        var neverCalled = new ScriptedGenerator(Json.write(draft("unused")));
        assertThrows(CancellationException.class, () -> new LlmCouncil(neverCalled).analyzeAgent(problem,
                Router.classify(problem), LensRegistry.load().getFirst(), "Analyze", context));
        assertTrue(neverCalled.calls.isEmpty());

        for (String raw : List.of(Json.write(draft("not-accepted")), "malformed")) {
            context.cancelled = false;
            var calls = new AtomicInteger();
            var council = new LlmCouncil((system, user, schema, maxTokens, attempt, ctx) -> {
                calls.incrementAndGet();
                context.cancelled = true;
                return raw;
            });
            assertThrows(CancellationException.class, () -> council.analyzeAgent(problem,
                    Router.classify(problem), LensRegistry.load().getFirst(), "Analyze", context));
            assertEquals(1, calls.get());
        }
    }

    private static CouncilState emptyState() {
        return new CouncilState(List.of(), List.of(), List.of(), List.of(), null, 0, "", List.of());
    }

    private static LocalLlmRuntime.LensDraft draft(String marker) {
        return new LocalLlmRuntime.LensDraft(marker + " view", List.of(marker + " hypothesis"),
                List.of(marker + " assumption"), List.of(marker + " evidence"), List.of(marker + " objection"),
                List.of(marker + " experiment"), 0.7);
    }

    private static LensOutput output(String id, String marker) {
        var draft = draft(marker);
        return new LensOutput(id, draft.coreView(), draft.hypotheses(), draft.assumptions(), draft.evidenceNeeded(),
                draft.counterarguments(), draft.experimentSuggestions(), draft.confidence());
    }

    private static ExperimentPlan plan() {
        return new ExperimentPlan("Reduce waste per attending student by 10%", "Grams per attending student",
                List.of("Daily waste weight", "Daily attendance"), "Matched baseline", "Pilot school", "4 weeks",
                "At least 10% reduction", "No improvement or unsafe food handling", List.of("Seasonal menus"));
    }

    private static FinalDecision decision() {
        return new FinalDecision("The pilot reduces waste per student by 10%", "Attendance measurement is unbiased",
                List.of("Daily attendance", "Weighed waste"), "Run one school pilot for four weeks",
                "At least 10% reduction", "No reduction or safety violation", "Scale only if the matched metric improves",
                "One school with a stable menu and direct cafeteria access");
    }

    private record Call(String system, String user, String schema, int maxTokens, int attempt) {}

    private static final class ScriptedGenerator implements LlmCouncil.Generator {
        private final Queue<String> responses;
        private final List<Call> calls = new ArrayList<>();

        private ScriptedGenerator(String... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public String generate(String system, String user, String schema, int maxTokens, int attempt, StageContext ctx) {
            calls.add(new Call(system, user, schema, maxTokens, attempt));
            if (responses.isEmpty()) {
                throw new AssertionError("Unexpected extra model request");
            }
            return responses.remove();
        }
    }

    private static final class MutableContext implements StageContext {
        private boolean cancelled;

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void onToken(String token) {}
    }
}
