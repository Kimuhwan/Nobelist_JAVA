package com.scientificlens.harness.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

/** Analysis depth. */
public enum Mode {
    FAST("fast", "빠름 (렌즈 5개)"),
    RESEARCH("research", "연구 (문제 유형별 5~7개)"),
    COUNCIL("council", "전체 위원회 (렌즈 15개)");

    private final String value;
    private final String label;

    Mode(String value, String label) {
        this.value = value;
        this.label = label;
    }

    @JsonValue
    public String value() {
        return value;
    }

    public String label() {
        return label;
    }

    @JsonCreator
    public static Mode fromValue(String value) {
        return Arrays.stream(values())
                .filter(m -> m.value.equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown mode: " + value));
    }

    @Override
    public String toString() {
        return label;
    }
}
