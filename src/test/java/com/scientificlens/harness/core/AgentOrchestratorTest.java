package com.scientificlens.harness.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import com.scientificlens.harness.domain.Mode;
import com.scientificlens.harness.domain.ProblemType;
import com.scientificlens.harness.domain.RedTeamFinding;
import com.scientificlens.harness.domain.Severity;
import com.scientificlens.harness.domain.UserProblem;
import com.scientificlens.harness.runtime.CouncilRuntime;
import com.scientificlens.harness.runtime.StageContext;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class AgentOrchestratorTest {
    private static final List<String> IDS = List.of("schrodinger", "oppenheimer", "planck", "pasteur", "faraday");
    private static final UserProblem PROBLEM = new UserProblem("Does the proposed intervention change a measured outcome?", Mode.FAST);
    private static final String CRITICISM = "The baseline was never measured; confidence cannot establish a causal effect.";

    @Test
    void managerSelectionAndTasksControlActualExecution() {
        ScriptedRuntime runtime = new ScriptedRuntime(happyPath());
        RecordingContext ctx = new RecordingContext();
        List<Integer> steps = new ArrayList<>();
        List<Integer> totals = new ArrayList<>();
        HarnessResult result = new Orchestrator().run(PROBLEM, runtime, (step, total, message) -> {
            steps.add(step);
            totals.add(total);
        }, ctx);

        assertEquals(IDS, result.selectedLenses());
        assertEquals(IDS, runtime.analyzedIds);
        assertEquals(IDS.stream().map(id -> "Measure the assumption assigned to " + id).toList(), runtime.tasks);
        assertNotEquals(Router.select(PROBLEM, LensRegistry.load()).stream().map(LensProfile::lensId).toList(), IDS);
        assertEquals(15, runtime.catalog.size());
        assertEquals(LensRegistry.load(), runtime.catalog);
        assertEquals(IDS, ctx.selected);
        assertTrue(runtime.selectionVisibleBeforeAnalysis);
        assertEquals(0, steps.getFirst());
        assertEquals(1, totals.getFirst());
        assertEquals(9, steps.getLast());
        assertEquals(9, totals.getLast());
        assertEquals(result.agentEvents(), ctx.events);
        for (int i = 0; i < result.agentEvents().size(); i++) {
            assertEquals(i + 1, result.agentEvents().get(i).sequence());
        }
        assertEquals("final_decision", result.agentEvents().getLast().action());
        assertTrue(result.agentEvents().stream().anyMatch(event -> event.action().equals("challenge")
                && event.content().contains(CRITICISM)));
        assertTrue(result.agentEvents().stream().anyMatch(event -> event.action().equals("reply")
                && event.content().contains("수정된 분석")));
        assertEquals(List.of("manager", "analyze", "analyze", "analyze", "analyze", "analyze",
                "manager", "challenge", "respond", "manager", "review", "manager", "experiment",
                "manager", "conclude"), runtime.calls);
    }

    @Test
    void exactCriticismAndOrderedTargetRevisionsReachEveryDownstreamPhase() {
        ScriptedRuntime runtime = new ScriptedRuntime(List.of(delegate(), debate(0, 1), debate(2, 1),
                action(CouncilAction.Type.RED_TEAM), action(CouncilAction.Type.EXPERIMENT), action(CouncilAction.Type.SYNTHESIZE)));
        HarnessResult result = run(runtime, new RecordingContext());

        assertEquals(2, runtime.challenges.size());
        assertEquals(2, result.lensRevisions().size());
        assertSame(runtime.challenges.getFirst(), runtime.receivedChallenges.getFirst());
        assertSame(runtime.challenges.getLast(), runtime.receivedChallenges.getLast());
        assertEquals(CRITICISM, runtime.receivedChallenges.getFirst().concern());
        assertEquals("original " + IDS.get(1), runtime.respondInputs.getFirst().coreView());
        assertEquals("revision 1: " + CRITICISM, runtime.respondInputs.getLast().coreView());
        LensOutput revised = result.lensOutputs().get(1);
        assertEquals("revision 2: " + CRITICISM, revised.coreView());
        for (int i = 0; i < result.lensRevisions().size(); i++) {
            LensRevision revision = result.lensRevisions().get(i);
            assertSame(runtime.challenges.get(i), revision.finding());
            assertSame(runtime.challengeTargetInputs.get(i), revision.before());
            assertSame(runtime.respondInputs.get(i), revision.before());
            assertEquals(runtime.replies.get(i).response(), revision.response());
            assertSame(runtime.replies.get(i).revisedOutput(), revision.after());
            assertEquals(result.debateFindings().get(i), revision.finding());
            assertEquals(List.of("core_view", "evidence_needed", "experiment_suggestions", "confidence"),
                    revision.changedFields());
            assertEquals(revision.before().assumptions(), revision.after().assumptions());
        }
        assertSame(runtime.analysisOutputs.get(1), result.lensRevisions().getFirst().before());
        assertSame(result.lensRevisions().getFirst().after(), result.lensRevisions().getLast().before());
        assertSame(revised, result.lensRevisions().getLast().after());
        assertSame(runtime.analysisOutputs.get(0), runtime.challengeSourceInputs.getFirst());
        assertSame(runtime.analysisOutputs.get(2), runtime.challengeSourceInputs.getLast());
        for (CouncilState state : List.of(runtime.reviewState, runtime.experimentState, runtime.concludeState)) {
            assertEquals(revised, state.outputs().get(1));
            assertEquals(2, state.completedDebates());
            assertEquals(runtime.challenges, state.debates());
            assertEquals(2, state.events().stream().filter(event -> event.action().equals("reply")).count());
        }
        assertEquals(runtime.redTeam, runtime.experimentState.redTeam());
        assertEquals(runtime.redTeam, runtime.concludeState.redTeam());
        assertEquals(runtime.plan, runtime.concludeState.experiment());
        assertEquals(List.of(), runtime.managerStates.getFirst().outputs());
        assertEquals("original " + IDS.get(1), runtime.managerStates.get(1).outputs().get(1).coreView());
        assertEquals("revision 1: " + CRITICISM, runtime.managerStates.get(2).outputs().get(1).coreView());
        assertThrows(UnsupportedOperationException.class, () -> runtime.reviewState.outputs().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.agentEvents().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.lensRevisions().clear());
    }

    @Test
    void acceptedUnchangedReplyIsCapturedWithoutInventingChangedFields() {
        ScriptedRuntime runtime = new ScriptedRuntime(happyPath());
        runtime.unchangedReply = true;
        HarnessResult result = run(runtime, new RecordingContext());
        LensRevision revision = result.lensRevisions().getFirst();
        assertSame(runtime.respondInputs.getFirst(), revision.before());
        assertSame(revision.before(), revision.after());
        assertEquals(runtime.replies.getFirst().response(), revision.response());
        assertEquals(List.of(), revision.changedFields());
        assertSame(revision.after(), runtime.reviewState.outputs().get(1));
        assertSame(revision.after(), result.lensOutputs().get(1));
    }

    @Test
    void invalidInitialIdCountDuplicateAndEarlySynthesisAreRejectedBeforeAnyChildDispatch() {
        List<AgentAssignment> unknown = new ArrayList<>(delegate().assignments());
        unknown.set(0, new AgentAssignment("invented_scientist", "Measure a baseline"));
        List<AgentAssignment> duplicate = new ArrayList<>(delegate().assignments());
        duplicate.set(0, duplicate.get(1));
        List<CouncilAction> invalid = List.of(
                new CouncilAction(CouncilAction.Type.DELEGATE, unknown, "", "", "Select valid lenses"),
                new CouncilAction(CouncilAction.Type.DELEGATE, duplicate, "", "", "Select distinct lenses"),
                new CouncilAction(CouncilAction.Type.DELEGATE, delegate().assignments().subList(0, 4), "", "", "Select five lenses"),
                action(CouncilAction.Type.SYNTHESIZE));
        for (CouncilAction rejected : invalid) {
            List<CouncilAction> script = new ArrayList<>();
            script.add(rejected);
            script.addAll(happyPath());
            ScriptedRuntime runtime = new ScriptedRuntime(script);
            HarnessResult result = run(runtime, new RecordingContext());
            assertEquals(List.of("manager", "manager"), runtime.calls.subList(0, 2));
            assertFalse(runtime.managerStates.get(1).feedback().isBlank());
            assertTrue(runtime.managerStates.get(1).assignments().isEmpty());
            assertEquals(IDS, runtime.analyzedIds);
            assertEquals(1, result.agentEvents().stream().filter(event -> event.action().equals("rejected")).count());
        }
    }

    @Test
    void invalidDebateIdsSelfChallengeAndDuplicateOrderedPairCannotDispatchAChallenge() {
        List<CouncilAction> invalid = List.of(
                new CouncilAction(CouncilAction.Type.DEBATE, List.of(), IDS.get(0), "einstein", "Check baseline"),
                new CouncilAction(CouncilAction.Type.DEBATE, List.of(), IDS.get(0), "unknown", "Check baseline"),
                debate(0, 0));
        for (CouncilAction rejected : invalid) {
            ScriptedRuntime runtime = new ScriptedRuntime(List.of(delegate(), rejected, debate(0, 1),
                    action(CouncilAction.Type.RED_TEAM), action(CouncilAction.Type.EXPERIMENT), action(CouncilAction.Type.SYNTHESIZE)));
            run(runtime, new RecordingContext());
            assertEquals(1, runtime.challenges.size());
            assertFalse(runtime.managerStates.get(2).feedback().isBlank());
        }

        ScriptedRuntime runtime = new ScriptedRuntime(List.of(delegate(), debate(0, 1), debate(0, 1),
                action(CouncilAction.Type.RED_TEAM), action(CouncilAction.Type.EXPERIMENT), action(CouncilAction.Type.SYNTHESIZE)));
        run(runtime, new RecordingContext());
        assertEquals(1, runtime.challenges.size());
        assertTrue(runtime.managerStates.get(3).feedback().contains("duplicate ordered debate pair"));
    }

    @Test
    void researchRequiresTwoActualDebatesBeforeReview() {
        ScriptedRuntime runtime = new ScriptedRuntime(List.of(delegate(), action(CouncilAction.Type.RED_TEAM),
                debate(0, 1), debate(1, 0), action(CouncilAction.Type.RED_TEAM),
                action(CouncilAction.Type.EXPERIMENT), action(CouncilAction.Type.SYNTHESIZE)));
        HarnessResult result = new Orchestrator().run(new UserProblem(PROBLEM.question(), Mode.RESEARCH), runtime,
                Orchestrator.Progress.NONE, new RecordingContext());
        assertEquals(2, result.debateFindings().size());
        assertEquals(2, runtime.reviewState.completedDebates());
        assertTrue(runtime.managerStates.get(2).feedback().contains("at least 2 completed debates"));
    }

    @Test
    void stagePrerequisitesAndDebateMaximumRejectBeforeCallingThoseTools() {
        List<List<CouncilAction>> scripts = List.of(
                List.of(delegate(), action(CouncilAction.Type.EXPERIMENT), debate(0, 1),
                        action(CouncilAction.Type.RED_TEAM), action(CouncilAction.Type.EXPERIMENT), action(CouncilAction.Type.SYNTHESIZE)),
                List.of(delegate(), debate(0, 1), action(CouncilAction.Type.SYNTHESIZE),
                        action(CouncilAction.Type.RED_TEAM), action(CouncilAction.Type.EXPERIMENT), action(CouncilAction.Type.SYNTHESIZE)),
                List.of(delegate(), debate(0, 1), action(CouncilAction.Type.RED_TEAM), debate(1, 0),
                        action(CouncilAction.Type.EXPERIMENT), action(CouncilAction.Type.SYNTHESIZE)),
                List.of(delegate(), debate(0, 1), debate(1, 0), debate(2, 0),
                        action(CouncilAction.Type.RED_TEAM), action(CouncilAction.Type.EXPERIMENT), action(CouncilAction.Type.SYNTHESIZE)));
        for (List<CouncilAction> script : scripts) {
            ScriptedRuntime runtime = new ScriptedRuntime(script);
            HarnessResult result = run(runtime, new RecordingContext());
            assertEquals(1, result.agentEvents().stream().filter(event -> event.action().equals("rejected")).count());
            assertEquals(1, runtime.calls.stream().filter(call -> call.equals("review")).count());
            assertEquals(1, runtime.calls.stream().filter(call -> call.equals("experiment")).count());
            assertEquals(1, runtime.calls.stream().filter(call -> call.equals("conclude")).count());
            assertTrue(runtime.challenges.size() <= 2);
        }
    }

    @Test
    void secondRejectedManagerActionFailsWithNoSyntheticSuccess() {
        ScriptedRuntime runtime = new ScriptedRuntime(List.of(action(CouncilAction.Type.SYNTHESIZE), action(CouncilAction.Type.SYNTHESIZE)));
        RecordingContext ctx = new RecordingContext();
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> run(runtime, ctx));
        assertTrue(error.getMessage().contains("rejected action limit reached (2)"));
        assertEquals(List.of("manager", "manager"), runtime.calls);
        assertEquals(2, ctx.events.size());
        assertFalse(ctx.events.stream().anyMatch(event -> event.action().equals("final_decision")));
    }

    @Test
    void managerTurnBudgetIsFiniteAndDoesNotRunAnUnbudgetedSynthesis() {
        ScriptedRuntime runtime = new ScriptedRuntime(List.of(delegate(), debate(0, 1), debate(1, 0),
                action(CouncilAction.Type.RED_TEAM), action(CouncilAction.Type.EXPERIMENT), action(CouncilAction.Type.SYNTHESIZE)));
        RecordingContext ctx = new RecordingContext();
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> new AgentOrchestrator(LensRegistry.load())
                .run(PROBLEM, runtime, Orchestrator.Progress.NONE, ctx, new CouncilLimits(5, 5, 1, 2, 5)));
        assertTrue(error.getMessage().contains("manager turn limit exhausted (5)"));
        assertEquals(5, runtime.managerStates.size());
        assertFalse(runtime.calls.contains("conclude"));
        assertFalse(ctx.events.stream().anyMatch(event -> event.action().equals("final_decision")));
    }

    @Test
    void cancellationBeforeAndAfterEveryRuntimeCallStopsWithoutFinish() {
        RecordingContext alreadyCancelled = new RecordingContext();
        alreadyCancelled.cancelled = true;
        ScriptedRuntime neverStarted = new ScriptedRuntime(happyPath());
        assertThrows(CancellationException.class, () -> run(neverStarted, alreadyCancelled));
        assertTrue(neverStarted.calls.isEmpty());

        for (String phase : List.of("manager", "analyze", "challenge", "respond", "review", "experiment", "conclude")) {
            RecordingContext ctx = new RecordingContext();
            ScriptedRuntime runtime = new ScriptedRuntime(happyPath());
            runtime.cancelAfter = phase;
            List<String> progress = new ArrayList<>();
            assertThrows(CancellationException.class, () -> new Orchestrator().run(PROBLEM, runtime,
                    (step, total, message) -> progress.add(message), ctx), phase);
            assertEquals(phase, runtime.calls.getLast());
            assertFalse(progress.contains("완료"), phase);
            assertFalse(ctx.events.stream().anyMatch(event -> event.action().equals("final_decision")), phase);
        }
    }

    @Test
    void cancelledSelectionAndEventHooksStopSubsequentDispatch() {
        RecordingContext selectionCancelled = new RecordingContext();
        selectionCancelled.cancelOnSelection = true;
        ScriptedRuntime selectionRuntime = new ScriptedRuntime(happyPath());
        assertThrows(CancellationException.class, () -> run(selectionRuntime, selectionCancelled));
        assertEquals(List.of("manager"), selectionRuntime.calls);

        RecordingContext eventCancelled = new RecordingContext();
        eventCancelled.cancelOnEvent = "reply";
        ScriptedRuntime eventRuntime = new ScriptedRuntime(happyPath());
        assertThrows(CancellationException.class, () -> run(eventRuntime, eventCancelled));
        assertEquals("respond", eventRuntime.calls.getLast());
        assertFalse(eventRuntime.calls.contains("review"));
    }

    @Test
    void failingChildOrManagerPropagatesAndNeverProducesSuccess() {
        for (String phase : List.of("manager", "analyze", "challenge", "respond", "review", "experiment", "conclude")) {
            RecordingContext ctx = new RecordingContext();
            ScriptedRuntime runtime = new ScriptedRuntime(happyPath());
            runtime.failOn = phase;
            IllegalStateException error = assertThrows(IllegalStateException.class, () -> run(runtime, ctx), phase);
            assertEquals("failed " + phase, error.getMessage());
            assertEquals(phase, runtime.calls.getLast());
            assertFalse(ctx.events.stream().anyMatch(event -> event.action().equals("final_decision")), phase);
        }
    }

    @Test
    void childCannotForgeDispatchedLensIds() {
        for (String phase : List.of("analyze", "challenge", "respond")) {
            ScriptedRuntime runtime = new ScriptedRuntime(happyPath());
            runtime.forgeIdOn = phase;
            RecordingContext ctx = new RecordingContext();
            assertThrows(IllegalStateException.class, () -> run(runtime, ctx), phase);
            assertEquals(phase, runtime.calls.getLast());
            assertFalse(runtime.calls.contains("review"));
            assertFalse(ctx.events.stream().anyMatch(event -> event.action().equals("reply")));
        }
    }

    @Test
    void legacyMockHasNoInventedCouncilTranscriptAndOldJsonRemainsReadable() throws Exception {
        HarnessResult legacy = Orchestrator.analyzeMock(PROBLEM);
        assertTrue(legacy.agentEvents().isEmpty());
        assertTrue(legacy.lensRevisions().isEmpty());
        var json = Json.MAPPER.valueToTree(legacy);
        ((com.fasterxml.jackson.databind.node.ObjectNode) json).remove("agent_events");
        ((com.fasterxml.jackson.databind.node.ObjectNode) json).remove("lens_revisions");
        HarnessResult restored = Json.MAPPER.treeToValue(json, HarnessResult.class);
        assertEquals(legacy, restored);
        assertTrue(restored.agentEvents().isEmpty());
        assertTrue(restored.lensRevisions().isEmpty());
        ((com.fasterxml.jackson.databind.node.ObjectNode) json).putNull("lens_revisions");
        assertEquals(legacy, Json.MAPPER.treeToValue(json, HarnessResult.class));
        HarnessResult nineArgument = new HarnessResult(legacy.problem(), legacy.problemType(), legacy.runtime(),
                legacy.selectedLenses(), legacy.lensOutputs(), legacy.debateFindings(), legacy.redTeamFindings(),
                legacy.experimentPlan(), legacy.finalDecision());
        HarnessResult tenArgument = new HarnessResult(legacy.problem(), legacy.problemType(), legacy.runtime(),
                legacy.selectedLenses(), legacy.lensOutputs(), legacy.debateFindings(), legacy.redTeamFindings(),
                legacy.experimentPlan(), legacy.finalDecision(), legacy.agentEvents());
        assertEquals(legacy, nineArgument);
        assertEquals(legacy, tenArgument);
        AgentEvent oldReply = new AgentEvent(1, legacy.selectedLenses().get(1), legacy.selectedLenses().get(0),
                "reply", "Old public transcript with a claimed revision and no typed snapshots");
        HarnessResult transcriptOnly = new HarnessResult(legacy.problem(), legacy.problemType(), legacy.runtime(),
                legacy.selectedLenses(), legacy.lensOutputs(), legacy.debateFindings(), legacy.redTeamFindings(),
                legacy.experimentPlan(), legacy.finalDecision(), List.of(oldReply));
        assertEquals(List.of(oldReply), transcriptOnly.agentEvents());
        assertTrue(transcriptOnly.lensRevisions().isEmpty());
        var transcriptJson = Json.MAPPER.valueToTree(transcriptOnly);
        ((com.fasterxml.jackson.databind.node.ObjectNode) transcriptJson).remove("lens_revisions");
        assertEquals(transcriptOnly, Json.MAPPER.treeToValue(transcriptJson, HarnessResult.class));
        assertEquals("debate", Json.MAPPER.readTree(Json.write(debate(0, 1))).path("type").asText());
        assertEquals(CouncilAction.Type.DEBATE, Json.MAPPER.readValue(Json.write(debate(0, 1)), CouncilAction.class).type());
        assertEquals(new CouncilLimits(5, 5, 1, 2, 8), CouncilLimits.forMode(Mode.FAST));
        assertEquals(new CouncilLimits(5, 7, 2, 3, 9), CouncilLimits.forMode(Mode.RESEARCH));
        assertEquals(new CouncilLimits(15, 15, 3, 5, 11), CouncilLimits.forMode(Mode.COUNCIL));
    }

    private static HarnessResult run(ScriptedRuntime runtime, RecordingContext ctx) {
        return new Orchestrator().run(PROBLEM, runtime, Orchestrator.Progress.NONE, ctx);
    }

    private static List<CouncilAction> happyPath() {
        return List.of(delegate(), debate(0, 1), action(CouncilAction.Type.RED_TEAM),
                action(CouncilAction.Type.EXPERIMENT), action(CouncilAction.Type.SYNTHESIZE));
    }

    private static CouncilAction delegate() {
        return new CouncilAction(CouncilAction.Type.DELEGATE,
                IDS.stream().map(id -> new AgentAssignment(id, "Measure the assumption assigned to " + id)).toList(),
                "", "", "Delegate independent analyses of measurable assumptions");
    }

    private static CouncilAction debate(int source, int target) {
        return new CouncilAction(CouncilAction.Type.DEBATE, List.of(), IDS.get(source), IDS.get(target), "Challenge the missing baseline");
    }

    private static CouncilAction action(CouncilAction.Type type) {
        return new CouncilAction(type, List.of(), "", "", "Use all accepted evidence and public critiques");
    }

    private static LensOutput output(String id, String view) {
        return new LensOutput(id, view, List.of("Measured treatment changes outcome"), List.of("Groups are comparable"),
                List.of("Baseline and comparison observations"), List.of("Selection bias"),
                List.of("Randomized controlled pilot"), 0.5);
    }

    private static final class RecordingContext implements StageContext {
        final List<AgentEvent> events = new ArrayList<>();
        List<String> selected = List.of();
        boolean cancelled;
        boolean cancelOnSelection;
        String cancelOnEvent = "";

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void onToken(String token) {}

        @Override
        public void onLensesSelected(List<String> lensIds) {
            selected = List.copyOf(lensIds);
            if (cancelOnSelection) {
                cancelled = true;
            }
        }

        @Override
        public void onAgentEvent(AgentEvent event) {
            events.add(event);
            if (event.action().equals(cancelOnEvent)) {
                cancelled = true;
            }
        }
    }

    private static final class ScriptedRuntime implements CouncilRuntime {
        final List<CouncilAction> script;
        final List<String> calls = new ArrayList<>();
        final List<String> analyzedIds = new ArrayList<>();
        final List<String> tasks = new ArrayList<>();
        final List<CouncilState> managerStates = new ArrayList<>();
        final List<DebateFinding> challenges = new ArrayList<>();
        final List<LensOutput> analysisOutputs = new ArrayList<>();
        final List<LensOutput> challengeSourceInputs = new ArrayList<>();
        final List<LensOutput> challengeTargetInputs = new ArrayList<>();
        final List<DebateFinding> receivedChallenges = new ArrayList<>();
        final List<LensOutput> respondInputs = new ArrayList<>();
        final List<CouncilReply> replies = new ArrayList<>();
        final List<RedTeamFinding> redTeam = List.of(new RedTeamFinding("Confounding remains", Severity.HIGH, "Randomize assignment"));
        final ExperimentPlan plan = new ExperimentPlan("Treatment changes measured outcome", "Outcome difference",
                List.of("Baseline", "Comparison outcomes"), "Existing process", "Intervention", "Two weeks",
                "Difference exceeds preregistered threshold", "No measurable difference", List.of("Selection bias"));
        List<LensProfile> catalog;
        CouncilState reviewState;
        CouncilState experimentState;
        CouncilState concludeState;
        boolean selectionVisibleBeforeAnalysis = true;
        boolean unchangedReply;
        String failOn = "";
        String cancelAfter = "";
        String forgeIdOn = "";

        ScriptedRuntime(List<CouncilAction> script) {
            this.script = List.copyOf(script);
        }

        private void called(String phase, StageContext ctx) {
            calls.add(phase);
            if (phase.equals(failOn)) {
                throw new IllegalStateException("failed " + phase);
            }
            if (phase.equals(cancelAfter)) {
                ((RecordingContext) ctx).cancelled = true;
            }
        }

        @Override
        public String name() {
            return "scripted_council";
        }

        @Override
        public CouncilAction nextAction(UserProblem problem, ProblemType type, List<LensProfile> catalog,
                CouncilState state, CouncilLimits limits, StageContext ctx) {
            called("manager", ctx);
            this.catalog = List.copyOf(catalog);
            managerStates.add(state);
            return script.get(managerStates.size() - 1);
        }

        @Override
        public LensOutput analyzeAgent(UserProblem problem, ProblemType type, LensProfile lens, String task, StageContext ctx) {
            called("analyze", ctx);
            analyzedIds.add(lens.lensId());
            tasks.add(task);
            selectionVisibleBeforeAnalysis &= ((RecordingContext) ctx).selected.equals(IDS);
            LensOutput analysis = output(forgeIdOn.equals("analyze") ? "einstein" : lens.lensId(), "original " + lens.lensId());
            analysisOutputs.add(analysis);
            return analysis;
        }

        @Override
        public DebateFinding challenge(UserProblem problem, LensProfile source, LensOutput sourceOutput,
                LensOutput targetOutput, String task, StageContext ctx) {
            called("challenge", ctx);
            assertEquals(source.lensId(), sourceOutput.lensId());
            assertEquals("Challenge the missing baseline", task);
            challengeSourceInputs.add(sourceOutput);
            challengeTargetInputs.add(targetOutput);
            DebateFinding finding = new DebateFinding(forgeIdOn.equals("challenge") ? "einstein" : source.lensId(),
                    targetOutput.lensId(), CRITICISM, "Measure a baseline and add a control");
            challenges.add(finding);
            return finding;
        }

        @Override
        public CouncilReply respond(UserProblem problem, LensProfile target, LensOutput current,
                DebateFinding challenge, StageContext ctx) {
            called("respond", ctx);
            receivedChallenges.add(challenge);
            respondInputs.add(current);
            assertEquals(target.lensId(), challenge.targetLensId());
            int revisionNumber = receivedChallenges.size();
            LensOutput revised = unchangedReply ? current : new LensOutput(
                    forgeIdOn.equals("respond") ? "einstein" : target.lensId(),
                    "revision " + revisionNumber + ": " + challenge.concern(),
                    current.hypotheses(), current.assumptions(),
                    List.of("Accepted baseline and comparison " + revisionNumber), current.counterarguments(),
                    List.of("Preregistered controlled pilot " + revisionNumber), 0.5 + revisionNumber * 0.1);
            CouncilReply reply = new CouncilReply("Response " + revisionNumber
                    + ": I require a measured baseline and comparison.\nThe response keeps the full accepted evidence.", revised);
            replies.add(reply);
            return reply;
        }

        @Override
        public List<RedTeamFinding> review(UserProblem problem, CouncilState state, String task, StageContext ctx) {
            called("review", ctx);
            reviewState = state;
            return redTeam;
        }

        @Override
        public ExperimentPlan experiment(UserProblem problem, CouncilState state, String task, StageContext ctx) {
            called("experiment", ctx);
            experimentState = state;
            return plan;
        }

        @Override
        public FinalDecision conclude(UserProblem problem, CouncilState state, String task, StageContext ctx) {
            called("conclude", ctx);
            concludeState = state;
            return new FinalDecision(plan.hypothesis(), "Groups are comparable", plan.requiredData(),
                    "Run a randomized pilot", plan.successCriteria(), plan.failureCriteria(),
                    "Continue only if the preregistered threshold is exceeded", "Start with a measurable small cohort");
        }

        @Override
        public LensOutput runLens(UserProblem problem, ProblemType type, LensProfile lens, StageContext ctx) {
            throw new AssertionError("legacy runLens must not be used for a council");
        }

        @Override
        public List<DebateFinding> debate(UserProblem problem, List<LensOutput> outputs, StageContext ctx) {
            throw new AssertionError("legacy fabricated debate must not be used for a council");
        }

        @Override
        public List<RedTeamFinding> redTeam(UserProblem problem, List<LensOutput> outputs, StageContext ctx) {
            throw new AssertionError("legacy redTeam must not be used for a council");
        }

        @Override
        public ExperimentPlan designExperiment(UserProblem problem, List<LensOutput> outputs,
                List<DebateFinding> debate, List<RedTeamFinding> redTeam, StageContext ctx) {
            throw new AssertionError("legacy designExperiment must not be used for a council");
        }

        @Override
        public FinalDecision synthesize(UserProblem problem, List<LensOutput> outputs, ExperimentPlan plan,
                List<RedTeamFinding> redTeam, StageContext ctx) {
            throw new AssertionError("legacy synthesize must not be used for a council");
        }
    }
}
