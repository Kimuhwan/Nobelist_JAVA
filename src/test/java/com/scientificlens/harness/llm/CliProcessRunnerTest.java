package com.scientificlens.harness.llm;

import static org.junit.jupiter.api.Assertions.*;

import com.scientificlens.harness.runtime.StageContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CliProcessRunnerTest {
    @Test
    void drainsBothPipesAndPreservesKoreanStreaming() throws Exception {
        StringBuffer tokens = new StringBuffer();
        StageContext ctx = context(new AtomicBoolean(), tokens);
        var result = CliProcessRunner.run(child("echo"), "한글 입력", ctx, Duration.ofSeconds(10));
        assertEquals(0,result.exitCode());
        assertEquals("한글 입력",result.stdout());
        assertEquals(result.stdout(),tokens.toString());
        assertEquals(100_000,result.stderr().length());
    }

    @Test
    void timesOutWhenNoOutputOrStdinConsumptionOccurs() throws Exception {
        Process process = child("silent");
        long started = System.nanoTime();
        LlmException error = assertThrows(LlmException.class, () -> CliProcessRunner.run(process,
                "입력".repeat(100_000), StageContext.NONE, Duration.ofMillis(350)));
        assertTrue(error.getMessage().contains("시간 초과"));
        assertTrue(System.nanoTime()-started < TimeUnit.SECONDS.toNanos(3));
        assertTrue(process.waitFor(3,TimeUnit.SECONDS));
        assertFalse(process.isAlive());
    }

    @Test
    void cancellationTerminatesASilentPendingChild() throws Exception {
        Process process = child("silent");
        AtomicBoolean cancelled = new AtomicBoolean();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> CliProcessRunner.run(process, "",
                    context(cancelled,new StringBuffer()),Duration.ofSeconds(30)));
            cancelled.set(true);
            ExecutionException error = assertThrows(ExecutionException.class, () -> future.get(3,TimeUnit.SECONDS));
            assertInstanceOf(CancellationException.class,error.getCause());
            assertTrue(process.waitFor(3,TimeUnit.SECONDS));
            assertFalse(process.isAlive());
        }
    }

    @Test
    void versionProbeHasADeadlineAndUsesTheExitCode() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                assertFalse(CliEngine.isInstalled(command("silent"), Duration.ofMillis(300))));
        assertTrue(CliEngine.isInstalled(command("echo"), Duration.ofSeconds(5)));
        assertFalse(CliEngine.isInstalled(command("failed-version"), Duration.ofSeconds(5)));
    }

    @Test
    void versionDetectionReadsTheExitedLauncherWithoutWaitingForItsDescendant(@TempDir Path directory) throws Exception {
        Path pid = directory.resolve("descendant.pid");
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                    assertEquals(CliEngine.parseVersion("codex-cli 2.3.4"),
                            CliEngine.probeVersion(command("inherited-output", pid.toString()), Duration.ofSeconds(3))));
        } finally {
            if (Files.exists(pid)) ProcessHandle.of(Long.parseLong(Files.readString(pid)))
                    .filter(ProcessHandle::isAlive).ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    @Test
    void interruptingAVersionProbeTerminatesItsProcessTree(@TempDir Path directory) throws Exception {
        Path pids = directory.resolve("ready");
        var result = new FutureTask<>(() -> {
            boolean installed = CliEngine.isInstalled(command("spawn", pids.toString()), Duration.ofSeconds(30));
            return !installed && Thread.currentThread().isInterrupted();
        });
        Thread check = Thread.startVirtualThread(result);
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (!Files.exists(pids) && System.nanoTime() < deadline) Thread.sleep(20);
            var processes = Files.readAllLines(pids).stream().map(Long::parseLong)
                    .map(pid -> ProcessHandle.of(pid).orElseThrow()).toList();
            try {
                check.interrupt();
                assertTrue(result.get(3, TimeUnit.SECONDS));
                for (ProcessHandle process : processes) process.onExit().get(3, TimeUnit.SECONDS);
            } finally {
                processes.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
            }
        } finally {
            check.interrupt();
        }
    }

    private static StageContext context(AtomicBoolean cancelled,StringBuffer tokens) {
        return new StageContext() {
            public boolean isCancelled() { return cancelled.get(); }
            public void onToken(String token) { tokens.append(token); }
        };
    }

    private static Process child(String behavior) throws Exception {
        return command(behavior).start();
    }

    private static ProcessBuilder command(String... arguments) {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-cp", System.getProperty("java.class.path"), Child.class.getName());
        builder.command().addAll(java.util.List.of(arguments));
        return builder;
    }

    public static final class Child {
        public static void main(String[] args) throws Exception {
            if (args[0].equals("silent")) { Thread.sleep(30_000); return; }
            if (args[0].equals("failed-version")) { System.exit(2); }
            if (args[0].equals("inherited-output")) {
                Process descendant = command("silent").inheritIO().start();
                Files.writeString(Path.of(args[1]), Long.toString(descendant.pid()));
                System.out.println("codex-cli 2.3.4");
                return;
            }
            if (args[0].equals("spawn")) {
                Process sleeper = child("silent");
                Path ready = Path.of(args[1]), temp = ready.resolveSibling("pids.tmp");
                Files.writeString(temp, ProcessHandle.current().pid() + "\n" + sleeper.pid());
                Files.move(temp, ready);
                Thread.sleep(30_000);
                return;
            }
            System.err.print("e".repeat(100_000));
            String input = new String(System.in.readAllBytes(),StandardCharsets.UTF_8);
            System.out.write(input.getBytes(StandardCharsets.UTF_8));
        }
    }
}
