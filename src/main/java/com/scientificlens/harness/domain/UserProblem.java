package com.scientificlens.harness.domain;

import java.util.Objects;

/** The user's question plus requested mode. {@code domainHint} is optional. */
public record UserProblem(String question, Mode mode, String domainHint) {
    public UserProblem {
        question = Checks.text(question, "question");
        Objects.requireNonNull(mode, "mode");
        domainHint = Checks.optionalText(domainHint);
    }

    public UserProblem(String question, Mode mode) {
        this(question, mode, null);
    }
}
