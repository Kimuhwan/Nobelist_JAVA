package com.scientificlens.harness.domain;

import java.util.Objects;

public record CouncilReply(String response, LensOutput revisedOutput) {
    public CouncilReply {
        response = Checks.text(response, "response");
        Objects.requireNonNull(revisedOutput, "revised_output");
    }
}
