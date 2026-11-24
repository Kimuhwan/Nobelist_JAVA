package com.scientificlens.harness.llm;

import com.scientificlens.harness.runtime.StageContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class CliProcessRunner {
    record Output(String stdout, String stderr, int exitCode) {}

    private CliProcessRunner() {}

    static Output run(Process process, String prompt, StageContext ctx, Duration timeout) {
        StringBuffer stdout = new StringBuffer();
        StringBuffer stderr = new StringBuffer();
        AtomicReference<RuntimeException> pumpFailure = new AtomicReference<>();
        Thread output = Thread.startVirtualThread(() -> drain(process.getInputStream(), stdout, ctx, pumpFailure));
        Thread errors = Thread.startVirtualThread(() -> drain(process.getErrorStream(), stderr, null, pumpFailure));
        Thread input = Thread.startVirtualThread(() -> {
            try (var stream = process.getOutputStream()) {
                if (prompt != null) stream.write(prompt.getBytes(StandardCharsets.UTF_8));
            } catch (IOException error) {
                pumpFailure.compareAndSet(null, new LlmException("CLI 입력 전송 실패", error));
            }
        });
        long started = System.nanoTime();
        try {
            while (true) {
                ctx.throwIfCancelled();
                if (System.nanoTime() - started >= timeout.toNanos()) {
                    throw new LlmException("CLI 응답 시간 초과 (" + timeout.toSeconds() + "초)");
                }
                if (process.waitFor(100, TimeUnit.MILLISECONDS)) break;
                RuntimeException failure = pumpFailure.get();
                if (failure != null) throw failure;
            }
            ctx.throwIfCancelled();
            output.join(2000); errors.join(2000); input.join(2000);
            if (output.isAlive() || errors.isAlive() || input.isAlive()) {
                throw new LlmException("CLI 출력 스트림이 종료되지 않았습니다.");
            }
            if (process.exitValue() == 0 && pumpFailure.get() != null) throw pumpFailure.get();
            return new Output(stdout.toString(), stderr.toString(), process.exitValue());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new CancellationException("analysis interrupted");
        } finally {
            stop(process);
            output.interrupt(); errors.interrupt(); input.interrupt();
        }
    }

    static void stop(Process process) {
        process.descendants().forEach(child -> {
            if (child.isAlive()) child.destroyForcibly();
        });
        if (process.isAlive()) process.destroyForcibly();
        close(process.getInputStream());
        close(process.getErrorStream());
        close(process.getOutputStream());
    }

    private static void drain(InputStream stream, StringBuffer destination, StageContext ctx,
            AtomicReference<RuntimeException> failure) {
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            char[] buffer = new char[512];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                String chunk = new String(buffer, 0, count);
                destination.append(chunk);
                if (ctx != null) ctx.onToken(chunk);
            }
        } catch (IOException error) {
            failure.compareAndSet(null, new LlmException("CLI 출력 읽기 실패", error));
        } catch (RuntimeException error) {
            failure.compareAndSet(null, error);
        }
    }

    private static void close(AutoCloseable stream) {
        try { stream.close(); }
        catch (Exception ignored) { }
    }
}
