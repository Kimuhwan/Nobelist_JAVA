package com.scientificlens.harness.llm;

import java.nio.file.Path;

/** Downloadable GGUF models known to work well with the embedded llama.cpp runtime. */
public enum ModelPreset {
    QWEN25_3B(
            "Qwen2.5 3B Instruct Q4_K_M (2.1 GB, 빠름, Qwen Research 라이선스: 비상업)",
            "qwen2.5-3b-instruct-q4_k_m.gguf",
            "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
            2_104_932_768L),
    QWEN25_7B(
            "Qwen2.5 7B Instruct Q4_K_M (4.7 GB, 품질 높음, Apache-2.0)",
            "Qwen2.5-7B-Instruct-Q4_K_M.gguf",
            "https://huggingface.co/bartowski/Qwen2.5-7B-Instruct-GGUF/resolve/main/Qwen2.5-7B-Instruct-Q4_K_M.gguf",
            4_683_074_240L);

    private final String label;
    private final String fileName;
    private final String url;
    private final long sizeBytes;

    ModelPreset(String label, String fileName, String url, long sizeBytes) {
        this.label = label;
        this.fileName = fileName;
        this.url = url;
        this.sizeBytes = sizeBytes;
    }

    public String fileName() {
        return fileName;
    }

    public String url() {
        return url;
    }

    public long sizeBytes() {
        return sizeBytes;
    }

    public Path defaultPath(Path modelsDir) {
        return modelsDir.resolve(fileName);
    }

    @Override
    public String toString() {
        return label;
    }
}
