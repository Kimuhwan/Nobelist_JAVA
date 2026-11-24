package com.scientificlens.harness.domain;

public record AgentAssignment(String lensId, String task) {
    public AgentAssignment {
        lensId = Checks.text(lensId, "lens_id");
        task = Checks.text(task, "task");
    }
}
