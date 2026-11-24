package com.scientificlens.harness.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public record CouncilAction(
        Type type,
        List<AgentAssignment> assignments,
        String sourceLensId,
        String targetLensId,
        String instruction) {
    public CouncilAction {
        Objects.requireNonNull(type, "type");
        assignments = assignments == null ? List.of() : List.copyOf(assignments);
        sourceLensId = sourceLensId == null ? "" : sourceLensId.strip();
        targetLensId = targetLensId == null ? "" : targetLensId.strip();
        instruction = Checks.text(instruction, "instruction");
    }

    public enum Type {
        DELEGATE("delegate"),
        DEBATE("debate"),
        RED_TEAM("red_team"),
        EXPERIMENT("experiment"),
        SYNTHESIZE("synthesize");

        private final String value;

        Type(String value) {
            this.value = value;
        }

        @JsonValue
        public String value() {
            return value;
        }

        @JsonCreator
        public static Type fromValue(String value) {
            return Arrays.stream(values())
                    .filter(type -> type.value.equalsIgnoreCase(value))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("unknown council action: " + value));
        }
    }
}
