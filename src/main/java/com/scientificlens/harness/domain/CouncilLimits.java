package com.scientificlens.harness.domain;

import java.util.Objects;

public record CouncilLimits(int minLenses, int maxLenses, int minDebates, int maxDebates, int maxManagerTurns) {
    public CouncilLimits {
        if (minLenses < 1 || maxLenses < minLenses || minDebates < 1 || maxDebates < minDebates
                || maxManagerTurns < minDebates + 4) {
            throw new IllegalArgumentException("invalid council limits");
        }
    }

    public static CouncilLimits forMode(Mode mode) {
        return switch (Objects.requireNonNull(mode, "mode")) {
            case FAST -> new CouncilLimits(5, 5, 1, 2, 8);
            case RESEARCH -> new CouncilLimits(5, 7, 2, 3, 9);
            case COUNCIL -> new CouncilLimits(15, 15, 3, 5, 11);
        };
    }
}
