package com.scientificlens.harness.llm;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.Properties;

/**
 * Which external CLI backend to use, persisted next to {@link LlmSettings} in
 * {@code ~/.scientific-lens-harness/cli.properties}.
 *
 * @param provider the agent CLI to shell out to
 * @param model model id to pass to that CLI (may be a preset or a custom string)
 */
public record CliSettings(CliProvider provider, String model) {
    private static final java.nio.file.Path FILE = LlmSettings.HOME.resolve("cli.properties");

    public CliSettings {
        if (model == null || model.isBlank()
                || provider == CliProvider.CODEX && model.startsWith("기본값")) {
            model = provider.defaultModel();
        }
    }

    public static CliSettings defaults() {
        return new CliSettings(CliProvider.KIRO, CliProvider.KIRO.defaultModel());
    }

    public static CliSettings load() {
        CliSettings d = defaults();
        if (!Files.exists(FILE)) {
            return d;
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(FILE)) {
            p.load(in);
            CliProvider provider = parseProvider(p.getProperty("provider"), d.provider());
            return new CliSettings(provider, p.getProperty("model", provider.defaultModel()));
        } catch (IOException | IllegalArgumentException e) {
            return d;
        }
    }

    public CliSettings with(CliProvider provider, String model) {
        return new CliSettings(provider, model);
    }

    public void save() {
        Properties p = new Properties();
        p.setProperty("provider", provider.name());
        p.setProperty("model", model);
        try {
            Files.createDirectories(LlmSettings.HOME);
            try (OutputStream out = Files.newOutputStream(FILE)) {
                p.store(out, "Scientific Lens Harness CLI backend settings");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static CliProvider parseProvider(String value, CliProvider fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return CliProvider.valueOf(value);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
