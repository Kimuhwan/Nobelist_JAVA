package com.scientificlens.harness.domain;

import java.util.Objects;

public record RedTeamFinding(String issue, Severity severity, String mitigation) {
    public RedTeamFinding {
        issue = Checks.text(issue, "issue");
        Objects.requireNonNull(severity, "severity");
        mitigation = Checks.text(mitigation, "mitigation");
    }
}
