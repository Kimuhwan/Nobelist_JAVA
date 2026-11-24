package com.scientificlens.harness.llm;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Local LLM settings persisted to {@code ~/.scientific-lens-harness/settings.properties}.
 *
 * @param modelPath GGUF file to load
 * @param threads CPU threads for inference
 * @param contextSize context window in tokens
 * @param temperature sampling temperature (low = more stable JSON)
 * @param seed base RNG seed for reproducible runs
 */
public record LlmSettings(Path modelPath, int threads, int contextSize, float temperature, int seed) {
    public static final Path HOME = Path.of(System.getProperty("user.home"), ".scientific-lens-harness");
    public static final Path MODELS_DIR = HOME.resolve("models");
    private static final Path FILE = HOME.resolve("settings.properties");

    public LlmSettings {
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be >= 1");
        }
        if (contextSize < 2048) {
            throw new IllegalArgumentException("contextSize must be >= 2048");
        }
        if (temperature < 0f || temperature > 2f) {
            throw new IllegalArgumentException("temperature must be within [0, 2]");
        }
    }

    public static LlmSettings defaults() {
        // Generation is memory-bandwidth bound; on an i7-12700H 6 threads beat 10. Override in settings.properties.
        int threads = Math.clamp(Runtime.getRuntime().availableProcessors() / 2, 2, 6);
        return new LlmSettings(ModelPreset.QWEN25_3B.defaultPath(MODELS_DIR), threads, 8192, 0.3f, 42);
    }

    public LlmSettings withModelPath(Path path) {
        return new LlmSettings(path, threads, contextSize, temperature, seed);
    }

    public static LlmSettings load() {
        LlmSettings d = defaults();
        if (!Files.exists(FILE)) {
            return d;
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(FILE)) {
            p.load(in);
            return new LlmSettings(
                    Path.of(p.getProperty("model.path", d.modelPath().toString())),
                    Integer.parseInt(p.getProperty("threads", String.valueOf(d.threads()))),
                    Integer.parseInt(p.getProperty("context.size", String.valueOf(d.contextSize()))),
                    Float.parseFloat(p.getProperty("temperature", String.valueOf(d.temperature()))),
                    Integer.parseInt(p.getProperty("seed", String.valueOf(d.seed()))));
        } catch (IOException | IllegalArgumentException e) {
            return d; // corrupt settings fall back to defaults
        }
    }

    public void save() {
        Properties p = new Properties();
        p.setProperty("model.path", modelPath.toString());
        p.setProperty("threads", String.valueOf(threads));
        p.setProperty("context.size", String.valueOf(contextSize));
        p.setProperty("temperature", String.valueOf(temperature));
        p.setProperty("seed", String.valueOf(seed));
        try {
            Files.createDirectories(HOME);
            try (OutputStream out = Files.newOutputStream(FILE)) {
                p.store(out, "Scientific Lens Harness local LLM settings");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
