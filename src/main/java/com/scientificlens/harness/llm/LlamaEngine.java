package com.scientificlens.harness.llm;

import com.scientificlens.harness.runtime.StageContext;
import de.kherud.llama.InferenceParameters;
import de.kherud.llama.LlamaIterator;
import de.kherud.llama.LlamaModel;
import de.kherud.llama.LlamaOutput;
import de.kherud.llama.ModelParameters;
import de.kherud.llama.Pair;
import de.kherud.llama.args.LogFormat;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * In-process llama.cpp engine (JNI). Loads one GGUF model and produces grammar-constrained JSON, so
 * outputs always parse against the stage schema. Calls are serialized: one model, one generation at a time.
 */
public final class LlamaEngine implements AutoCloseable {
    private final LlamaModel model;
    private final LlmSettings settings;
    private final Map<String, String> grammarCache = new ConcurrentHashMap<>();

    private LlamaEngine(LlamaModel model, LlmSettings settings) {
        this.model = model;
        this.settings = settings;
    }

    public static LlamaEngine load(LlmSettings settings) {
        if (!Files.isRegularFile(settings.modelPath())) {
            throw new LlmException("모델 파일이 없습니다: " + settings.modelPath());
        }
        LlamaModel.setLogger(LogFormat.TEXT, (level, message) -> {}); // silence native logging
        ModelParameters params = new ModelParameters()
                .setModel(settings.modelPath().toString())
                .setCtxSize(settings.contextSize())
                .setThreads(settings.threads())
                .setThreadsBatch(settings.threads())
                .disableLog();
        try {
            return new LlamaEngine(new LlamaModel(params), settings);
        } catch (RuntimeException e) {
            throw new LlmException("모델 로딩 실패: " + e.getMessage(), e);
        }
    }

    public LlmSettings settings() {
        return settings;
    }

    /**
     * Generates a JSON document conforming to {@code jsonSchema}.
     *
     * @param attempt retry index, mixed into the seed so retries explore a different sample
     * @param abortIf checked on the partial output once it passes {@link #EARLY_CHECK_CHARS}; returning
     *     true stops generation immediately and this method returns {@code null}
     */
    public synchronized String completeJson(String system, String user, String jsonSchema, int maxTokens,
            int attempt, Predicate<String> abortIf, StageContext ctx) {
        String grammar = grammarCache.computeIfAbsent(jsonSchema, LlamaModel::jsonSchemaToGrammar);
        InferenceParameters params = new InferenceParameters(formatChat(system, user))
                .setGrammar(grammar)
                .setTemperature(settings.temperature())
                .setNPredict(maxTokens)
                .setSeed(settings.seed() + attempt * 7919);
        StringBuilder text = new StringBuilder();
        boolean checked = false;
        LlamaIterator iterator = model.generate(params).iterator();
        while (iterator.hasNext()) {
            if (ctx.isCancelled()) {
                iterator.cancel();
                throw new CancellationException("analysis cancelled");
            }
            LlamaOutput output = iterator.next();
            text.append(output.text);
            ctx.onToken(output.text);
            if (!checked && text.length() >= EARLY_CHECK_CHARS) {
                checked = true;
                if (abortIf.test(text.toString())) {
                    iterator.cancel();
                    return null;
                }
            }
        }
        return text.toString();
    }

    public static final int EARLY_CHECK_CHARS = 160;

    /** Uses the model's embedded chat template; falls back to ChatML (Qwen, many others). */
    private String formatChat(String system, String user) {
        try {
            InferenceParameters template = new InferenceParameters("")
                    .setMessages(system, List.of(new Pair<>("user", user)));
            String prompt = model.applyTemplate(template);
            if (prompt != null && !prompt.isBlank()) {
                return prompt;
            }
        } catch (RuntimeException ignored) {
            // fall through to ChatML
        }
        return "<|im_start|>system\n" + system + "<|im_end|>\n<|im_start|>user\n" + user
                + "<|im_end|>\n<|im_start|>assistant\n";
    }

    @Override
    public void close() {
        model.close();
    }
}
