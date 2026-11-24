package com.scientificlens.harness.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.scientificlens.harness.domain.HarnessResult;
import com.scientificlens.harness.domain.StoredAnalysis;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** 기기에 저장한 분석 보고서를 조회하고 메모, 숨김 상태와 외부 JSON 가져오기를 관리합니다. */
public final class AnalysisLibrary {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final Pattern ID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern OWNED_FILE = Pattern.compile(ID.pattern() + "\\.json");
    private static final ObjectMapper MAPPER = strictMapper();

    private final Path directory;

    public AnalysisLibrary(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
    }

    private static ObjectMapper strictMapper() {
        ObjectMapper mapper = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .enable(SerializationFeature.INDENT_OUTPUT)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .build();
        mapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        return mapper;
    }

    public synchronized StoredAnalysis save(HarnessResult result) throws IOException {
        if (result == null) {
            throw new IOException("Cannot save an empty report");
        }
        return create(result, titleFor(result), "", "generated");
    }

    public synchronized LibraryListing list(boolean archived) throws IOException {
        Path folder = folder(archived);
        if (!Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) {
            return new LibraryListing(List.of(), List.of());
        }
        requireDirectory(directory);
        requireDirectory(folder);
        List<Path> files;
        try (var children = Files.list(folder)) {
            files = children.filter(path -> OWNED_FILE.matcher(path.getFileName().toString()).matches())
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
        List<StoredAnalysis> entries = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (Path path : files) {
            try {
                entries.add(readOwned(path));
            } catch (IOException e) {
                warnings.add(path.getFileName() + ": " + e.getMessage());
            }
        }
        entries.sort(Comparator.comparing((StoredAnalysis item) -> Instant.parse(item.savedAt()))
                .reversed().thenComparing(StoredAnalysis::id));
        return new LibraryListing(entries, warnings);
    }

    /**
     * 저장된 보고서를 읽어 원래의 질문, 결과와 공개 실행 기록을 복원합니다.
     * @param id 보관함이 발급한 보고서 ID
     * @param archived 숨겨진 보고서를 읽을 때 참
     * @return 저장 정보와 분석 원문
     * @throws IOException 지정된 보고서를 읽거나 검증할 수 없는 경우
     */
    public synchronized StoredAnalysis read(String id, boolean archived) throws IOException {
        Path path = recordPath(id, archived);
        requireDirectory(directory);
        requireDirectory(path.getParent());
        return readOwned(path);
    }

    public synchronized StoredAnalysis edit(String id, String title, String notes) throws IOException {
        StoredAnalysis existing = read(id, false);
        StoredAnalysis replacement;
        try {
            replacement = new StoredAnalysis(1, existing.id(), existing.savedAt(), Instant.now().toString(),
                    title, notes, existing.origin(), existing.result());
        } catch (IllegalArgumentException e) {
            throw new IOException("Cannot edit report metadata: " + e.getMessage(), e);
        }
        write(replacement, true);
        return replacement;
    }

    public synchronized StoredAnalysis importReport(Path input) throws IOException {
        if (input == null) {
            throw new IOException("Choose a JSON report to import");
        }
        JsonNode node = parse(input.toAbsolutePath().normalize());
        if (node.has("schema_version") || node.has("result")) {
            StoredAnalysis bundle = decodeBundle(node);
            return create(bundle.result(), bundle.title(), bundle.notes(), "imported");
        }
        HarnessResult result = decodeResult(node);
        return create(result, titleFor(result), "", "imported");
    }

    public synchronized void archive(String id) throws IOException {
        move(id, false);
    }

    public synchronized void restore(String id) throws IOException {
        move(id, true);
    }

    private StoredAnalysis create(HarnessResult result, String title, String notes, String origin)
            throws IOException {
        String now = Instant.now().toString();
        StoredAnalysis stored = new StoredAnalysis(1, UUID.randomUUID().toString(), now, now,
                title, notes, origin, result);
        write(stored, false);
        return stored;
    }

    private static String titleFor(HarnessResult result) {
        String question = result.problem().question();
        if (question.codePointCount(0, question.length()) <= 160) {
            return question;
        }
        return question.substring(0, question.offsetByCodePoints(0, 159)) + "…";
    }

    private Path folder(boolean archived) {
        return archived ? directory.resolve("archived") : directory;
    }

    private Path recordPath(String id, boolean archived) throws IOException {
        if (id == null || !ID.matcher(id).matches() || !UUID.fromString(id).toString().equals(id)) {
            throw new IOException("Report ID must be a canonical lowercase UUID");
        }
        Path folder = folder(archived);
        Path path = folder.resolve(id + ".json").normalize();
        if (!path.getParent().equals(folder) || !path.startsWith(directory)) {
            throw new IOException("Report path must stay inside the library");
        }
        return path;
    }

    private static void requireDirectory(Path folder) throws IOException {
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Library directory is missing, inaccessible, or is a symbolic link: "
                    + folder.getFileName());
        }
    }

    private void createDirectory(Path folder) throws IOException {
        Files.createDirectories(directory);
        requireDirectory(directory);
        if (!folder.equals(directory)) {
            Files.createDirectories(folder);
            requireDirectory(folder);
        }
    }

    private StoredAnalysis readOwned(Path path) throws IOException {
        StoredAnalysis stored = decodeBundle(parse(path));
        if (!path.getFileName().toString().equals(stored.id() + ".json")) {
            throw new IOException("Stored report ID does not match its filename");
        }
        return stored;
    }

    private static JsonNode parse(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Report file is missing, inaccessible, or is not a regular file");
        }
        if (Files.size(path) > MAX_BYTES) {
            throw new IOException("Report file exceeds the 16 MiB limit");
        }
        byte[] bytes;
        try (InputStream input = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(MAX_BYTES + 1);
        }
        if (bytes.length > MAX_BYTES) {
            throw new IOException("Report file exceeds the 16 MiB limit");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IOException("Report file must contain valid UTF-8", e);
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IOException("Report file contains malformed JSON", e);
        }
        if (node == null || !node.isObject()) {
            throw new IOException("Report JSON must be an object");
        }
        return node;
    }

    private static StoredAnalysis decodeBundle(JsonNode node) throws IOException {
        JsonNode schema = node.get("schema_version");
        if (schema == null || !schema.isIntegralNumber() || !schema.canConvertToInt()) {
            throw new IOException("Stored report must declare an integer schema_version");
        }
        if (schema.intValue() != 1) {
            throw new IOException("Unsupported stored report schema version: " + schema.intValue());
        }
        for (String field : List.of("id", "saved_at", "updated_at", "title", "notes", "origin")) {
            if (!node.has(field) || !node.get(field).isTextual()) {
                throw new IOException("Stored report metadata must contain text field: " + field);
            }
        }
        if (!node.has("result") || !node.get("result").isObject()) {
            throw new IOException("Stored report must contain a complete result object");
        }
        validateResultShape(node.get("result"));
        try {
            return MAPPER.treeToValue(node, StoredAnalysis.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw invalidData("stored report", e);
        }
    }

    private static HarnessResult decodeResult(JsonNode node) throws IOException {
        validateResultShape(node);
        try {
            return MAPPER.treeToValue(node, HarnessResult.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw invalidData("analysis result", e);
        }
    }

    private static void validateResultShape(JsonNode result) throws IOException {
        JsonNode outputs = result.get("lens_outputs");
        if (outputs != null && outputs.isArray()) {
            for (JsonNode output : outputs) {
                requireConfidence(output);
            }
        }
        JsonNode revisions = result.get("lens_revisions");
        if (revisions != null && revisions.isArray()) {
            for (JsonNode revision : revisions) {
                requireConfidence(revision.path("before"));
                requireConfidence(revision.path("after"));
            }
        }
    }

    private static void requireConfidence(JsonNode output) throws IOException {
        if (!output.isObject() || !output.has("confidence") || !output.get("confidence").isNumber()) {
            throw new IOException("Every lens output must contain numeric confidence");
        }
    }

    private static IOException invalidData(String kind, Exception cause) {
        Throwable root = cause;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String detail = root instanceof IllegalArgumentException || root instanceof NullPointerException
                ? ": " + root.getMessage() : "";
        return new IOException("Invalid or incomplete " + kind + detail, cause);
    }

    private void write(StoredAnalysis stored, boolean replace) throws IOException {
        byte[] bytes = MAPPER.writeValueAsBytes(stored);
        if (bytes.length > MAX_BYTES) {
            throw new IOException("Saved report exceeds the 16 MiB limit");
        }
        Path target = recordPath(stored.id(), false);
        createDirectory(directory);
        if (replace) {
            readOwned(target);
        } else if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("A report with this ID already exists");
        }
        Path temporary = Files.createTempFile(directory, ".analysis-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            if (replace) {
                replace(temporary, target);
            } else {
                publishNew(temporary, target);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void publishNew(Path temporary, Path target) throws IOException {
        try {
            // Creating a hard link atomically publishes complete bytes without replacing an existing ID.
            Files.createLink(target, temporary);
        } catch (FileAlreadyExistsException e) {
            throw new IOException("A report with this ID already exists", e);
        } catch (UnsupportedOperationException | IOException e) {
            // Same-directory rename is the safe no-replacement fallback for filesystems without links.
            Files.move(temporary, target);
        }
    }

    private static void replace(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            replaceWithBackup(temporary, target);
        }
    }

    private static void replaceWithBackup(Path temporary, Path target) throws IOException {
        Path backup = Files.createTempFile(target.getParent(), ".analysis-backup-", ".tmp");
        boolean keepBackup = false;
        try {
            Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException failure) {
                try {
                    Files.move(backup, target, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException recoveryFailure) {
                    keepBackup = true;
                    failure.addSuppressed(recoveryFailure);
                    throw new IOException("Metadata replacement failed; the original report is retained in "
                            + backup.getFileName(), failure);
                }
                throw new IOException("Metadata replacement failed; the original report was preserved", failure);
            }
        } finally {
            if (!keepBackup) {
                Files.deleteIfExists(backup);
            }
        }
    }

    private void move(String id, boolean fromArchived) throws IOException {
        Path source = recordPath(id, fromArchived);
        read(id, fromArchived);
        Path destination = recordPath(id, !fromArchived);
        createDirectory(destination.getParent());
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Cannot " + (fromArchived ? "restore" : "archive")
                    + " report: a file with this ID already exists at the destination");
        }
        // No ATOMIC_MOVE flag: that flag may replace an existing target even without REPLACE_EXISTING.
        Files.move(source, destination);
    }
}
