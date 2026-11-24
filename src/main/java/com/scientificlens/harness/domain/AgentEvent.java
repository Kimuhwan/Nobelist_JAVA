package com.scientificlens.harness.domain;

public record AgentEvent(int sequence, String actor, String recipient, String action, String content) {
    public AgentEvent {
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        actor = Checks.text(actor, "actor");
        recipient = Checks.text(recipient, "recipient");
        action = Checks.text(action, "action");
        content = Checks.text(content, "content");
    }
}
