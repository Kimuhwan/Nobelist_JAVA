package com.scientificlens.harness.domain;

import com.fasterxml.jackson.annotation.JsonValue;

public enum ProblemType {
    PRODUCT_VALIDATION("product_validation"),
    RESEARCH_HYPOTHESIS("research_hypothesis"),
    BUSINESS_SYSTEM("business_system"),
    RISK_ETHICS("risk_ethics"),
    GENERAL("general");

    private final String value;

    ProblemType(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
