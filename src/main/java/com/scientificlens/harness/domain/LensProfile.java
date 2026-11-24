package com.scientificlens.harness.domain;

import java.util.List;

/** A methodological lens inspired by a historical scientist (not an impersonation). */
public record LensProfile(
        String lensId,
        String displayName,
        String thinkingStyle,
        String responsibility,
        List<String> routingTags,
        String promptGuidance) {
    public LensProfile {
        lensId = Checks.text(lensId, "lens_id");
        displayName = Checks.text(displayName, "display_name");
        thinkingStyle = Checks.text(thinkingStyle, "thinking_style");
        responsibility = Checks.text(responsibility, "responsibility");
        routingTags = Checks.items(routingTags, "routing_tags");
        promptGuidance = Checks.text(promptGuidance, "prompt_guidance");
    }
}
