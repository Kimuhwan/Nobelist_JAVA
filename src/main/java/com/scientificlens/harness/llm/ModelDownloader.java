package com.scientificlens.harness.llm;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/** Resumable HTTPS download of a GGUF model to a {@code .part} file, then atomic rename. */
public final class ModelDownloader {
    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public Path download(ModelPreset preset, Path target, LongConsumer bytesSoFar, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        if (Files.exists(target) && Files.size(target) == preset.sizeBytes()) {
            bytesSoFar.accept(preset.sizeBytes());
            return target;
        }
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path part = target.resolveSibling(target.getFileName() + ".part");
        long existing = Files.exists(part) ? Files.size(part) : 0L;

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(preset.url()))
                .header("User-Agent", "scientific-lens-harness/0.1")
                .GET();
        if (existing > 0) {
            request.header("Range", "bytes=" + existing + "-");
        }
        HttpResponse<InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        boolean append = response.statusCode() == 206;
        if (!append && response.statusCode() != 200) {
            response.body().close();
            throw new IOException("download failed: HTTP " + response.statusCode());
        }
        long written = append ? existing : 0L;
        StandardOpenOption mode = append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING;
        try (InputStream in = response.body();
                OutputStream out = Files.newOutputStream(part, StandardOpenOption.CREATE, StandardOpenOption.WRITE, mode)) {
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (cancelled.getAsBoolean()) {
                    throw new CancellationException("download cancelled");
                }
                out.write(buffer, 0, read);
                written += read;
                bytesSoFar.accept(written);
            }
        }
        if (written != preset.sizeBytes()) {
            throw new IOException("incomplete download: " + written + " of " + preset.sizeBytes() + " bytes");
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return target;
    }
}
