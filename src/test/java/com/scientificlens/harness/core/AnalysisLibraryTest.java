package com.scientificlens.harness.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scientificlens.harness.domain.AgentEvent;
import com.scientificlens.harness.domain.HarnessResult;
import com.scientificlens.harness.domain.Mode;
import com.scientificlens.harness.domain.StoredAnalysis;
import com.scientificlens.harness.domain.UserProblem;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnalysisLibraryTest {
    private static final int MAX_BYTES = 16 * 1024 * 1024;

    @TempDir
    Path temporary;

    @Test
    void emptyListingDoesNotCreateDirectories() throws Exception {
        Path root = temporary.resolve("missing").resolve("library");
        AnalysisLibrary library = new AnalysisLibrary(root);
        assertEquals(new LibraryListing(List.of(), List.of()), library.list(false));
        assertEquals(new LibraryListing(List.of(), List.of()), library.list(true));
        assertFalse(Files.exists(root));
    }

    @Test
    void savedReportPersistsAcrossLibraryInstances() throws Exception {
        Path root = temporary.resolve("library");
        HarnessResult result = result("농산물 폐기량을 줄이는 실험 🔬");
        StoredAnalysis saved = new AnalysisLibrary(root.resolve("ignored").resolve("..")).save(result);
        AnalysisLibrary reopened = new AnalysisLibrary(root);

        assertEquals(saved, reopened.read(saved.id(), false));
        assertEquals(result, saved.result());
        assertEquals(result.problem().question(), saved.title());
        assertEquals("generated", saved.origin());
        assertEquals("", saved.notes());
        assertEquals(1, saved.schemaVersion());
        assertEquals(UUID.fromString(saved.id()).toString(), saved.id());
        assertEquals(Instant.parse(saved.savedAt()), Instant.parse(saved.updatedAt()));
        assertEquals(List.of(saved), reopened.list(false).entries());
        assertTrue(reopened.list(false).unreadableFiles().isEmpty());
        assertOnlyOwnedFiles(root, 1);
    }

    @Test
    void longQuestionRemainsCompleteAndTitleUsesUnicodeCodePoints() throws Exception {
        String question = "🔬".repeat(170);
        StoredAnalysis saved = new AnalysisLibrary(temporary.resolve("library")).save(result(question));
        assertEquals(question, saved.result().problem().question());
        assertEquals("🔬".repeat(159) + "…", saved.title());
        assertEquals(160, saved.title().codePointCount(0, saved.title().length()));
    }

    @Test
    void editPreservesExactUnicodeNotesAndAllReportData() throws Exception {
        Path root = temporary.resolve("library");
        AnalysisLibrary library = new AnalysisLibrary(root);
        StoredAnalysis saved = library.save(result("Original complete question"));
        String notes = "  내 메모 🔬\r\n\tγ = 0.2\n한글 조합: 한\n\n  ";
        StoredAnalysis edited = library.edit(saved.id(), "Revised title 🔬", notes);
        StoredAnalysis reopened = new AnalysisLibrary(root).read(saved.id(), false);

        assertEquals(edited, reopened);
        assertEquals(notes, reopened.notes());
        assertEquals("Revised title 🔬", reopened.title());
        assertEquals(saved.id(), reopened.id());
        assertEquals(saved.savedAt(), reopened.savedAt());
        assertEquals(saved.origin(), reopened.origin());
        assertEquals(saved.result(), reopened.result());
        assertFalse(Instant.parse(reopened.updatedAt()).isBefore(Instant.parse(saved.updatedAt())));
        assertOnlyOwnedFiles(root, 1);
    }

    @Test
    void rejectedMetadataPreservesTheExistingValidFileExactly() throws Exception {
        Path root = temporary.resolve("library");
        AnalysisLibrary library = new AnalysisLibrary(root);
        StoredAnalysis saved = library.save(result("Save before failed edit"));
        Path file = root.resolve(saved.id() + ".json");
        byte[] original = Files.readAllBytes(file);

        assertThrows(IOException.class, () -> library.edit(saved.id(), " ", "changed"));
        assertThrows(IOException.class, () -> library.edit(saved.id(), "New title", "x".repeat(64_001)));
        assertThrows(IOException.class, () -> library.edit(saved.id(), "🔬".repeat(161), "changed"));
        assertArrayEquals(original, Files.readAllBytes(file));
        assertEquals(saved, new AnalysisLibrary(root).read(saved.id(), false));
        assertOnlyOwnedFiles(root, 1);
    }

    @Test
    void metadataWriteSizeFailurePreservesTheExistingValidFileExactly() throws Exception {
        Path root = temporary.resolve("library");
        AnalysisLibrary library = new AnalysisLibrary(root);
        HarnessResult base = result("Large report metadata edit");
        int baselineSize = Json.MAPPER.writeValueAsBytes(base).length;
        HarnessResult large = new HarnessResult(base.problem(), base.problemType(),
                "r".repeat(MAX_BYTES - baselineSize - 1_024), base.selectedLenses(), base.lensOutputs(),
                base.debateFindings(), base.redTeamFindings(), base.experimentPlan(), base.finalDecision(),
                base.agentEvents());
        StoredAnalysis saved = library.save(large);
        Path file = root.resolve(saved.id() + ".json");
        byte[] original = Files.readAllBytes(file);

        IOException failure = assertThrows(IOException.class,
                () -> library.edit(saved.id(), "New title", "n".repeat(64_000)));
        assertTrue(failure.getMessage().contains("16 MiB"));
        assertArrayEquals(original, Files.readAllBytes(file));
        assertEquals(saved, new AnalysisLibrary(root).read(saved.id(), false));
        assertOnlyOwnedFiles(root, 1);
    }

    @Test
    void listingSortsBySavingTimeWithStableUuidTieBreakAndIsImmutable() throws Exception {
        Path root = temporary.resolve("library");
        AnalysisLibrary library = new AnalysisLibrary(root);
        Files.createDirectories(root);
        HarnessResult result = result("Ordering test");
        StoredAnalysis first = record("00000000-0000-0000-0000-000000000001", "2026-01-01T00:00:00Z", result);
        StoredAnalysis second = record("00000000-0000-0000-0000-000000000002", "2026-01-02T00:00:00Z", result);
        StoredAnalysis third = record("00000000-0000-0000-0000-000000000003", "2026-01-02T00:00:00Z", result);
        write(root.resolve(third.id() + ".json"), third);
        write(root.resolve(first.id() + ".json"), first);
        write(root.resolve(second.id() + ".json"), second);

        library.edit(first.id(), "Most recently edited", "still earliest saved");
        LibraryListing listing = library.list(false);
        assertEquals(List.of(second.id(), third.id(), first.id()), listing.entries().stream()
                .map(StoredAnalysis::id).toList());
        assertThrows(UnsupportedOperationException.class, () -> listing.entries().clear());
        assertThrows(UnsupportedOperationException.class, () -> listing.unreadableFiles().add("hidden"));
    }

    @Test
    void corruptOwnedRecordsProduceVisibleWarningsAndUnrelatedFilesAreIgnored() throws Exception {
        Path root = temporary.resolve("library");
        AnalysisLibrary library = new AnalysisLibrary(root);
        StoredAnalysis saved = library.save(result("Valid report"));
        String corruptId = "00000000-0000-0000-0000-000000000001";
        String unsupportedId = "00000000-0000-0000-0000-000000000002";
        String mismatchId = "00000000-0000-0000-0000-000000000003";
        Files.writeString(root.resolve(corruptId + ".json"), "{ broken", StandardCharsets.UTF_8);
        ObjectNode unsupported = Json.MAPPER.valueToTree(saved);
        unsupported.put("id", unsupportedId);
        unsupported.put("schema_version", 2);
        write(root.resolve(unsupportedId + ".json"), unsupported);
        write(root.resolve(mismatchId + ".json"), saved);
        Files.writeString(root.resolve("unrelated.json"), "{ broken", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("ABCDEFAB-CDEF-ABCD-EFAB-CDEFABCDEFAB.json"), "{ broken", StandardCharsets.UTF_8);
        Files.createDirectory(root.resolve("archived"));

        LibraryListing listing = library.list(false);
        assertEquals(List.of(saved), listing.entries());
        assertEquals(3, listing.unreadableFiles().size());
        assertTrue(listing.unreadableFiles().get(0).startsWith(corruptId + ".json:"));
        assertTrue(listing.unreadableFiles().get(1).contains("Unsupported stored report schema version: 2"));
        assertTrue(listing.unreadableFiles().get(2).contains("does not match its filename"));
        assertThrows(IOException.class, () -> library.read(corruptId, false));
        assertThrows(IOException.class, () -> library.archive(corruptId));
        assertTrue(Files.exists(root.resolve(corruptId + ".json")));
    }

    @Test
    void oldRawJsonImportsCompletelyWithoutLegacyOptionalFields() throws Exception {
        Path root = temporary.resolve("library");
        HarnessResult original = result("Legacy analysis question");
        ObjectNode oldJson = Json.MAPPER.valueToTree(original);
        oldJson.remove("agent_events");
        oldJson.remove("lens_revisions");
        Path input = temporary.resolve("legacy.json");
        write(input, oldJson);

        StoredAnalysis imported = new AnalysisLibrary(root).importReport(input);
        assertEquals(original, imported.result());
        assertEquals("imported", imported.origin());
        assertEquals(original.problem().question(), imported.title());
        assertEquals(imported, new AnalysisLibrary(root).read(imported.id(), false));
        assertOnlyOwnedFiles(root, 1);
    }

    @Test
    void currentRawJsonImportsFullPublicEventData() throws Exception {
        Path root = temporary.resolve("library");
        HarnessResult base = result("Current analysis format");
        HarnessResult original = new HarnessResult(base.problem(), base.problemType(), "test-runtime",
                base.selectedLenses(), base.lensOutputs(), base.debateFindings(), base.redTeamFindings(),
                base.experimentPlan(), base.finalDecision(), List.of(
                        new AgentEvent(1, "coordinator", "lens", "assignment", "Complete public request 🔬"),
                        new AgentEvent(2, "lens", "coordinator", "response", "Complete public response\nsecond line")));
        Path input = temporary.resolve("current.json");
        write(input, original);

        StoredAnalysis imported = new AnalysisLibrary(root).importReport(input);
        assertEquals(original, imported.result());
        assertEquals(original.agentEvents(), new AnalysisLibrary(root).read(imported.id(), false)
                .result().agentEvents());
        assertEquals("imported", imported.origin());
    }

    @Test
    void storedBundleImportsWithFreshIdAndNeverOverwritesTheSourceRecord() throws Exception {
        Path root = temporary.resolve("library");
        AnalysisLibrary library = new AnalysisLibrary(root);
        StoredAnalysis saved = library.save(result("Import collision test"));
        StoredAnalysis source = library.edit(saved.id(), "Custom imported title", " \tlocal notes 🔬\n ");
        Path existingFile = root.resolve(source.id() + ".json");
        byte[] original = Files.readAllBytes(existingFile);

        StoredAnalysis first = library.importReport(existingFile);
        StoredAnalysis second = library.importReport(existingFile);
        assertNotEquals(source.id(), first.id());
        assertNotEquals(source.id(), second.id());
        assertNotEquals(first.id(), second.id());
        assertEquals("imported", first.origin());
        assertEquals(source.title(), first.title());
        assertEquals(source.notes(), first.notes());
        assertEquals(source.result(), first.result());
        assertEquals(first, new AnalysisLibrary(root).read(first.id(), false));
        assertArrayEquals(original, Files.readAllBytes(existingFile));
        assertEquals(3, library.list(false).entries().size());
        assertOnlyOwnedFiles(root, 3);
    }

    @Test
    void incompleteUnknownAndUnsupportedImportsAreRejectedWithoutCreatingLibrary() throws Exception {
        Path root = temporary.resolve("library");
        AnalysisLibrary library = new AnalysisLibrary(root);
        Path input = temporary.resolve("invalid.json");
        ObjectNode incomplete = Json.MAPPER.valueToTree(result("Incomplete test"));
        ((ObjectNode) incomplete.get("final_decision")).remove("first_experiment");
        write(input, incomplete);
        assertTrue(assertThrows(IOException.class, () -> library.importReport(input)).getMessage()
                .contains("first_experiment"));

        ObjectNode unknown = Json.MAPPER.valueToTree(result("Unknown field test"));
        unknown.put("invented_field", "unsupported data must not be silently dropped");
        write(input, unknown);
        assertThrows(IOException.class, () -> library.importReport(input));

        ObjectNode missingConfidence = Json.MAPPER.valueToTree(result("Missing confidence"));
        ((ObjectNode) missingConfidence.get("lens_outputs").get(0)).remove("confidence");
        write(input, missingConfidence);
        assertTrue(assertThrows(IOException.class, () -> library.importReport(input)).getMessage()
                .contains("confidence"));

        ObjectNode coercedText = Json.MAPPER.valueToTree(result("Invalid numeric question"));
        ((ObjectNode) coercedText.get("problem")).put("question", 123);
        write(input, coercedText);
        assertThrows(IOException.class, () -> library.importReport(input));

        ObjectNode unsupported = Json.MAPPER.valueToTree(record(UUID.randomUUID().toString(),
                "2026-01-01T00:00:00Z", result("Unsupported bundle")));
        unsupported.put("schema_version", 9);
        write(input, unsupported);
        assertTrue(assertThrows(IOException.class, () -> library.importReport(input)).getMessage()
                .contains("schema version: 9"));
        assertFalse(Files.exists(root));
    }

    @Test
    void malformedUtf8DuplicateKeysAndTrailingJsonAreRejected() throws Exception {
        AnalysisLibrary library = new AnalysisLibrary(temporary.resolve("library"));
        Path input = temporary.resolve("invalid.json");
        Files.write(input, new byte[] {(byte) 0xc3, (byte) 0x28});
        assertTrue(assertThrows(IOException.class, () -> library.importReport(input)).getMessage().contains("UTF-8"));
        Files.writeString(input, "{\"problem\":{},\"problem\":{}}", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> library.importReport(input));
        Files.writeString(input, Json.write(result("Trailing test")) + " {}", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> library.importReport(input));
        Files.writeString(input, "[]", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> library.importReport(input));
    }

    @Test
    void importAndListingRejectFilesAboveSixteenMebibytes() throws Exception {
        Path root = temporary.resolve("library");
        AnalysisLibrary library = new AnalysisLibrary(root);
        Path input = temporary.resolve("oversized.json");
        try (RandomAccessFile file = new RandomAccessFile(input.toFile(), "rw")) {
            file.setLength(MAX_BYTES + 1L);
        }
        assertTrue(assertThrows(IOException.class, () -> library.importReport(input)).getMessage()
                .contains("16 MiB"));
        Files.createDirectories(root);
        String id = UUID.randomUUID().toString();
        Files.move(input, root.resolve(id + ".json"));
        assertEquals(1, library.list(false).unreadableFiles().size());
        assertTrue(library.list(false).unreadableFiles().getFirst().contains("16 MiB"));
        assertThrows(IOException.class, () -> library.read(id, false));
    }

    @Test
    void invalidIdsAreRejectedBeforeAnyPathResolutionOrDirectoryCreation() throws Exception {
        Path root = temporary.resolve("library");
        AnalysisLibrary library = new AnalysisLibrary(root);
        String uuid = "abcdefab-cdef-abcd-efab-cdefabcdefab";
        for (String id : List.of("../outside", uuid + "/../../outside", uuid.toUpperCase(),
                "1-1-1-1-1", "{" + uuid + "}", "", "C:\\outside")) {
            assertTrue(assertThrows(IOException.class, () -> library.read(id, false)).getMessage().contains("UUID"));
            assertThrows(IOException.class, () -> library.edit(id, "title", "notes"));
            assertThrows(IOException.class, () -> library.archive(id));
            assertThrows(IOException.class, () -> library.restore(id));
        }
        assertThrows(IOException.class, () -> library.read(null, true));
        assertFalse(Files.exists(root));
    }

    @Test
    void archiveAndRestoreRetainEveryByteAcrossInstances() throws Exception {
        Path root = temporary.resolve("library");
        AnalysisLibrary library = new AnalysisLibrary(root);
        StoredAnalysis saved = library.save(result("Reversible archive"));
        saved = library.edit(saved.id(), "Archive title", " \nnotes 🔬\n ");
        String id = saved.id();
        Path active = root.resolve(id + ".json");
        Path archived = root.resolve("archived").resolve(id + ".json");
        byte[] original = Files.readAllBytes(active);
        library.archive(id);

        AnalysisLibrary reopened = new AnalysisLibrary(root);
        assertFalse(Files.exists(active));
        assertArrayEquals(original, Files.readAllBytes(archived));
        assertEquals(List.of(saved), reopened.list(true).entries());
        assertTrue(reopened.list(false).entries().isEmpty());
        assertEquals(saved, reopened.read(id, true));
        assertThrows(IOException.class, () -> reopened.edit(id, "cannot edit archived", ""));
        reopened.restore(id);
        assertFalse(Files.exists(archived));
        assertArrayEquals(original, Files.readAllBytes(active));
        assertEquals(saved, new AnalysisLibrary(root).read(id, false));
        assertTrue(reopened.list(true).entries().isEmpty());
    }

    @Test
    void archiveAndRestoreCollisionsNeverReplaceEitherFile() throws Exception {
        Path root = temporary.resolve("library");
        AnalysisLibrary library = new AnalysisLibrary(root);
        StoredAnalysis saved = library.save(result("Collision protection"));
        Path active = root.resolve(saved.id() + ".json");
        Path archived = root.resolve("archived").resolve(saved.id() + ".json");
        byte[] original = Files.readAllBytes(active);
        Files.createDirectories(archived.getParent());
        Files.writeString(archived, "Destination sentinel", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> library.archive(saved.id()));
        assertArrayEquals(original, Files.readAllBytes(active));
        assertEquals("Destination sentinel", Files.readString(archived, StandardCharsets.UTF_8));

        Files.delete(archived);
        library.archive(saved.id());
        Files.writeString(active, "Active sentinel", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> library.restore(saved.id()));
        assertArrayEquals(original, Files.readAllBytes(archived));
        assertEquals("Active sentinel", Files.readString(active, StandardCharsets.UTF_8));
    }

    @Test
    void metadataRecordRejectsInvalidSchemaTimeOriginAndLimits() {
        HarnessResult result = result("Metadata validation");
        String id = UUID.randomUUID().toString();
        String time = "2026-01-01T00:00:00Z";
        assertThrows(IllegalArgumentException.class,
                () -> new StoredAnalysis(2, id, time, time, "title", "", "generated", result));
        assertThrows(IllegalArgumentException.class,
                () -> new StoredAnalysis(1, "../outside", time, time, "title", "", "generated", result));
        assertThrows(IllegalArgumentException.class,
                () -> new StoredAnalysis(1, id, "yesterday", time, "title", "", "generated", result));
        assertThrows(IllegalArgumentException.class,
                () -> new StoredAnalysis(1, id, time, null, "title", "", "generated", result));
        assertThrows(IllegalArgumentException.class,
                () -> new StoredAnalysis(1, id, time, time, "title", "", "replay", result));
        assertThrows(IllegalArgumentException.class,
                () -> new StoredAnalysis(1, id, time, time, "title", null, "generated", result));
        assertThrows(IllegalArgumentException.class,
                () -> new StoredAnalysis(1, id, time, time, "title", "", "generated", null));
        assertEquals("🔬".repeat(160), new StoredAnalysis(1, id, time, time,
                "🔬".repeat(160), "", "generated", result).title());
    }

    private static HarnessResult result(String question) {
        return Orchestrator.analyzeMock(new UserProblem(question, Mode.FAST));
    }

    private static StoredAnalysis record(String id, String savedAt, HarnessResult result) {
        return new StoredAnalysis(1, id, savedAt, savedAt, result.problem().question(), "", "generated", result);
    }

    private static void write(Path path, Object value) throws IOException {
        Files.writeString(path, Json.write(value), StandardCharsets.UTF_8);
    }

    private static void assertOnlyOwnedFiles(Path root, int expected) throws IOException {
        try (var children = Files.list(root)) {
            List<Path> files = children.toList();
            assertEquals(expected, files.size());
            assertTrue(files.stream().allMatch(path -> path.getFileName().toString()
                    .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.json")));
        }
    }
}
