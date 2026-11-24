package com.scientificlens.harness.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class JsonExtractTest {
    @Test
    void plainObject() {
        assertEquals("{\"a\":1}", JsonExtract.firstObject("{\"a\":1}"));
    }

    @Test
    void objectWrappedInProse() {
        String text = "Here is the result:\n{\"a\":1,\"b\":\"x\"}\nHope that helps.";
        assertEquals("{\"a\":1,\"b\":\"x\"}", JsonExtract.firstObject(text));
    }

    @Test
    void objectInsideCodeFence() {
        String text = "```json\n{\"a\": [1, 2], \"b\": {\"c\": 3}}\n```";
        assertEquals("{\"a\": [1, 2], \"b\": {\"c\": 3}}", JsonExtract.firstObject(text));
    }

    @Test
    void bracesInsideStringsDoNotBreakBalancing() {
        String text = "{\"note\":\"use { and } carefully\",\"n\":1}";
        assertEquals(text, JsonExtract.firstObject(text));
    }

    @Test
    void escapedQuoteInsideString() {
        String text = "{\"q\":\"she said \\\"hi\\\" then {left\"}";
        assertEquals(text, JsonExtract.firstObject(text));
    }

    @Test
    void nestedObjects() {
        String text = "prefix {\"outer\":{\"inner\":{\"deep\":true}}} suffix";
        assertEquals("{\"outer\":{\"inner\":{\"deep\":true}}}", JsonExtract.firstObject(text));
    }

    @Test
    void noObjectReturnsNull() {
        assertNull(JsonExtract.firstObject("no json here"));
        assertNull(JsonExtract.firstObject(null));
    }

    @Test
    void unbalancedReturnsNull() {
        assertNull(JsonExtract.firstObject("{\"a\":1"));
    }
}
