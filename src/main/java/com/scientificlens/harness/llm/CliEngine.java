package com.scientificlens.harness.llm;

import com.scientificlens.harness.runtime.StageContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Runs a one-shot prompt through an external agent CLI (see {@link CliProvider}) and returns the
 * model's answer. Output streams to the {@link StageContext} live, and cancellation destroys the
 * child process. No API keys are handled here: the CLI carries its own OAuth session.
 */
public final class CliEngine {
    /** A single CLI call can involve model reasoning + startup; generous but bounded. */
    private static final long TIMEOUT_MINUTES = 8;

    private final CliProvider provider;
    private final String model;
    private final String reasoningEffort;

    public CliEngine(CliProvider provider, String model) {
        this(provider, model, "medium");
    }

    public CliEngine(CliProvider provider, String model, String reasoningEffort) {
        if (!List.of("low", "medium", "high").contains(reasoningEffort)) {
            throw new IllegalArgumentException("unsupported reasoning effort: " + reasoningEffort);
        }
        this.provider = provider;
        this.model = model;
        this.reasoningEffort = reasoningEffort;
    }

    public CliProvider provider() {
        return provider;
    }

    public String model() {
        return model;
    }

    /** Whether the CLI executable is resolvable on PATH. Cheap {@code --version} probe. */
    public static boolean isInstalled(CliProvider provider) {
        return isInstalled(new ProcessBuilder(resolveExecutable(provider.executable()), "--version"),
                Duration.ofSeconds(20));
    }

    static boolean isInstalled(ProcessBuilder command, Duration timeout) {
        return runVersionProbe(command.redirectOutput(ProcessBuilder.Redirect.DISCARD), timeout) != null;
    }

    /**
     * Sends {@code system + user} to the CLI and returns its raw textual answer. The system prompt is
     * prepended to the user prompt because these CLIs take a single prompt string.
     */
    public String complete(String system, String user, StageContext ctx) {
        String prompt = system.strip() + "\n\n" + user.strip();
        Path lastMessageFile = null;
        List<String> args = new ArrayList<>();
        args.add(resolveExecutable(provider.executable()));
        if (provider == CliProvider.CODEX) {
            args.add("-c");
            args.add("model_reasoning_effort=" + reasoningEffort);
        }

        List<String> providerArgs = provider.command(model, null);
        if (providerArgs.contains(CliProvider.LAST_MESSAGE_PLACEHOLDER)) {
            try {
                lastMessageFile = Files.createTempFile("slh-cli-", ".txt");
            } catch (IOException e) {
                throw new LlmException("임시 파일 생성 실패: " + e.getMessage(), e);
            }
        }
        for (String a : providerArgs) {
            args.add(a.equals(CliProvider.LAST_MESSAGE_PLACEHOLDER)
                    ? String.valueOf(lastMessageFile)
                    : a);
        }

        Path invocationDirectory;
        try {
            invocationDirectory = Files.createTempDirectory("slh-inference-");
        } catch (IOException e) {
            cleanup(lastMessageFile);
            throw new LlmException("분석 작업 폴더 생성 실패", e);
        }
        Process process;
        try {
            process = new ProcessBuilder(args)
                    .directory(invocationDirectory.toFile())
                    .redirectErrorStream(false)
                    .start();
        } catch (IOException e) {
            cleanup(lastMessageFile);
            cleanup(invocationDirectory);
            throw new LlmException(provider.label() + " 실행 실패: " + provider.executable()
                    + " 를 PATH에서 찾을 수 없거나 시작할 수 없습니다. (" + e.getMessage() + ")", e);
        }

        try {
            CliProcessRunner.Output output = CliProcessRunner.run(process,
                    provider.promptOnStdin() ? prompt : null, ctx, java.time.Duration.ofMinutes(TIMEOUT_MINUTES));
            if (output.exitCode() != 0) {
                String stderr = output.stderr();
                String tail = stderr.length() > 600 ? stderr.substring(stderr.length() - 600) : stderr;
                throw new LlmException(provider.label() + " 오류 (exit " + output.exitCode() + "): " + tail.strip());
            }
            String answer = provider.extractAnswer(output.stdout(), lastMessageFile);
            if (answer == null || answer.isBlank()) {
                throw new LlmException(provider.label() + " 가 빈 응답을 반환했습니다.");
            }
            return answer;
        } finally {
            cleanup(lastMessageFile);
            cleanup(invocationDirectory);
        }
    }

    private static void cleanup(Path file) {
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // temp file cleanup is best-effort
            }
        }
    }
    private static String cachedCodexExecutable;
    private static final Pattern CODEX_VERSION =
            Pattern.compile("codex-cli\\s+(\\d+)\\.(\\d+)\\.(\\d+)(-[^\\s]+)?");

    /** Java cannot launch npm's extensionless shell shim; compare native Windows installations. */
    private static synchronized String resolveExecutable(String name) {
        if (!"codex".equals(name)
                || !System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows")) {
            return name;
        }
        if (cachedCodexExecutable != null && Files.isRegularFile(Path.of(cachedCodexExecutable))) {
            return cachedCodexExecutable;
        }
        LinkedHashSet<Path> candidates = codexPathCandidates(System.getenv().getOrDefault("PATH", ""));
        Path local = environmentDirectory(System.getenv("LOCALAPPDATA"));
        if (local != null) {
            Path desktop = local.resolve(Path.of("OpenAI", "Codex", "bin"));
            if (Files.isDirectory(desktop)) {
                try (var directories = Files.list(desktop)) {
                    directories.filter(Files::isDirectory)
                            .map(directory -> directory.resolve("codex.exe")).forEach(candidates::add);
                } catch (IOException e) {
                    // PATH candidates remain usable if the desktop installation cannot be read.
                }
            }
        }
        Path roaming = environmentDirectory(System.getenv("APPDATA"));
        if (roaming != null) {
            for (String architecture : List.of("x64", "arm64")) {
                String target = architecture.equals("x64") ? "x86_64" : "aarch64";
                candidates.add(roaming.resolve(Path.of("npm", "node_modules", "@openai", "codex",
                        "node_modules", "@openai", "codex-win32-" + architecture, "vendor",
                        target + "-pc-windows-msvc", "bin", "codex.exe")));
            }
        }
        String selected = name;
        Version newest = null;
        for (Path candidate : candidates) {
            if (!Files.isRegularFile(candidate)) continue;
            Version version = probeVersion(new ProcessBuilder(candidate.toString(), "--version"), Duration.ofSeconds(5));
            if (Thread.currentThread().isInterrupted()) return name;
            if (version != null && (newest == null || version.compareTo(newest) > 0)) {
                newest = version;
                selected = candidate.toAbsolutePath().toString();
            }
        }
        cachedCodexExecutable = selected;
        return selected;
    }

    static LinkedHashSet<Path> codexPathCandidates(String path) {
        LinkedHashSet<Path> candidates = new LinkedHashSet<>();
        for (String entry : path.split(java.io.File.pathSeparator)) {
            Path directory = environmentDirectory(entry.replace("\"", ""));
            if (directory != null) candidates.add(directory.resolve("codex.exe"));
        }
        return candidates;
    }

    private static Path environmentDirectory(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Path.of(value);
        } catch (InvalidPathException e) {
            return null;
        }
    }

    static Version probeVersion(ProcessBuilder command, Duration timeout) {
        String output = runVersionProbe(command, timeout);
        return output == null ? null : parseVersion(output);
    }

    private static String runVersionProbe(ProcessBuilder command, Duration timeout) {
        if (Thread.currentThread().isInterrupted()) return null;
        Process process = null;
        try {
            process = command.redirectErrorStream(true).start();
            process.getOutputStream().close();
            if (!process.waitFor(timeout) || process.exitValue() != 0) return null;
            // A launcher may exit while a descendant still has the output pipe open.
            var output = process.getInputStream();
            return new String(output.readNBytes(output.available()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (process != null) CliProcessRunner.stop(process);
        }
    }

    static Version parseVersion(String output) {
        var match = CODEX_VERSION.matcher(output);
        if (!match.find()) return null;
        try {
            return new Version(Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)),
                    Integer.parseInt(match.group(3)), match.group(4) == null);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    record Version(int major, int minor, int patch, boolean stable) implements Comparable<Version> {
        @Override
        public int compareTo(Version other) {
            int result = Integer.compare(major, other.major);
            if (result == 0) result = Integer.compare(minor, other.minor);
            if (result == 0) result = Integer.compare(patch, other.patch);
            return result != 0 ? result : Boolean.compare(stable, other.stable);
        }
    }
}
