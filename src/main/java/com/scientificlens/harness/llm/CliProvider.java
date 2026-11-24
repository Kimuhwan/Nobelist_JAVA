package com.scientificlens.harness.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * An external, already-authenticated agent CLI that can answer a single prompt non-interactively.
 *
 * <p>These are not API integrations: we shell out to a CLI the user has installed and logged into
 * (OAuth handled by the tool itself), so the harness needs no API key of its own. Each provider knows
 * how to build a one-shot command, which models it offers, and how to recover the model's final text
 * from the CLI's stdout.
 */
public enum CliProvider {
    /**
     * Kiro CLI. Non-interactive chat prints the assistant's answer as plain text on stdout.
     * Tools are disabled ({@code --trust-tools=}) so it only reasons and replies.
     */
    KIRO(
            "Kiro CLI",
            "kiro-cli",
            List.of(
                    "auto",
                    "claude-opus-5.5",
                    "claude-opus-5",
                    "claude-sonnet-5",
                    "gpt-5.6-sol",
                    "gpt-5.6-terra",
                    "gpt-5.6-luna"),
            "auto") {
        @Override
        public List<String> command(String model, Path workdir) {
            // prompt is supplied on stdin ("-" style not needed: kiro reads INPUT arg OR stdin; we pipe stdin)
            List<String> args = new java.util.ArrayList<>(List.of(
                    "chat", "--no-interactive", "--trust-tools="));
            if (model != null && !model.isBlank() && !model.equals("default")) {
                args.add("--model");
                args.add(model);
            }
            return args;
        }

        @Override
        public boolean promptOnStdin() {
            return true;
        }

        @Override
        public String extractAnswer(String stdout, Path lastMessageFile) {
            return stdout; // stdout is already just the assistant reply
        }
    },

    /** Codex reads the user's CLI configuration unless an explicit model is selected. */
    CODEX(
            "OpenAI Codex CLI (GPT)",
            "codex",
            List.of("기본값 (CLI 설정)", "gpt-6.1-sol", "gpt-6-astra", "gpt-6-sol", "gpt-6-luna"),
            "기본값 (CLI 설정)") {
        @Override
        public List<String> command(String model, Path workdir) {
            List<String> args = new java.util.ArrayList<>(List.of(
                    "exec",
                    "--skip-git-repo-check",
                    "--ephemeral",
                    "-s", "read-only"));
            if (model != null && !model.isBlank() && !model.equals("default")
                    && !model.startsWith("기본값")) {
                args.add("-m");
                args.add(model);
            }
            // final message written to the marker file (see CliEngine); prompt read from stdin
            args.add("-o");
            args.add(LAST_MESSAGE_PLACEHOLDER);
            args.add("-"); // read prompt from stdin
            return args;
        }

        @Override
        public boolean promptOnStdin() {
            return true;
        }

        @Override
        public String extractAnswer(String stdout, Path lastMessageFile) {
            String fromFile = readIfPresent(lastMessageFile);
            return fromFile != null && !fromFile.isBlank() ? fromFile : stdout;
        }
    };

    /** Replaced by {@link CliEngine} with the actual per-run marker file path. */
    public static final String LAST_MESSAGE_PLACEHOLDER = "__LAST_MESSAGE_FILE__";

    private final String label;
    private final String executable;
    private final List<String> models;
    private final String defaultModel;

    CliProvider(String label, String executable, List<String> models, String defaultModel) {
        this.label = label;
        this.executable = executable;
        this.models = models;
        this.defaultModel = defaultModel;
    }

    public String label() {
        return label;
    }

    /** Base executable name resolved on PATH (no {@code .exe}; ProcessBuilder finds it on Windows). */
    public String executable() {
        return executable;
    }

    /** Curated models known to be selectable for this CLI. The user can also type a custom id. */
    public List<String> models() {
        if (this != CODEX) {
            return models;
        }
        String home = System.getenv("CODEX_HOME");
        Path cache = home == null || home.isBlank()
                ? Path.of(System.getProperty("user.home"), ".codex", "models_cache.json")
                : Path.of(home, "models_cache.json");
        return codexModels(cache);
    }

    static List<String> codexModels(Path cache) {
        LinkedHashSet<String> available = new LinkedHashSet<>();
        available.add(CODEX.defaultModel());
        try {
            var entries = new ObjectMapper().readTree(cache.toFile()).path("models");
            for (var entry : entries) {
                String slug = entry.path("slug").asText("").strip();
                if (!slug.isBlank() && !"hide".equals(entry.path("visibility").asText())) {
                    available.add(slug);
                }
            }
        } catch (IOException e) {
            return CODEX.models;
        }
        return available.size() > 1 ? List.copyOf(available) : CODEX.models;
    }

    public String defaultModel() {
        return defaultModel;
    }

    /** Arguments after the executable for a one-shot, non-interactive run of {@code model}. */
    public abstract List<String> command(String model, Path workdir);

    /** True if the prompt should be piped to the process stdin rather than passed as an argument. */
    public abstract boolean promptOnStdin();

    /**
     * Recovers the model's final answer. Some CLIs print only the answer to stdout; others also print
     * an event log and expose the clean answer through a marker file.
     */
    public abstract String extractAnswer(String stdout, Path lastMessageFile);

    private static String readIfPresent(Path file) {
        if (file == null) {
            return null;
        }
        try {
            return java.nio.file.Files.exists(file)
                    ? java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8)
                    : null;
        } catch (java.io.IOException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return label;
    }
}
