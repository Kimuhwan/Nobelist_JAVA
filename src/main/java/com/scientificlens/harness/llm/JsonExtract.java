package com.scientificlens.harness.llm;

/**
 * Recovers a single JSON object from free-form text. External CLIs may wrap the answer in prose or a
 * markdown code fence, so we strip fences and then scan for the first balanced {@code { ... }} block,
 * respecting strings and escapes so braces inside string values do not confuse the matcher.
 */
final class JsonExtract {
    private JsonExtract() {}

    static String firstObject(String text) {
        if (text == null) {
            return null;
        }
        String cleaned = stripFences(text);
        int start = cleaned.indexOf('{');
        while (start >= 0) {
            String block = balancedFrom(cleaned, start);
            if (block != null) {
                return block;
            }
            start = cleaned.indexOf('{', start + 1);
        }
        return null;
    }

    /** Removes ```json ... ``` (or bare ```) fences, keeping their inner content. */
    private static String stripFences(String text) {
        String t = text.strip();
        int fence = t.indexOf("```");
        if (fence < 0) {
            return t;
        }
        int firstNl = t.indexOf('\n', fence);
        if (firstNl < 0) {
            return t;
        }
        int closing = t.indexOf("```", firstNl);
        return closing < 0 ? t.substring(firstNl + 1) : t.substring(firstNl + 1, closing);
    }

    /** Returns the balanced object beginning at {@code start}, or null if it never closes. */
    private static String balancedFrom(String s, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return s.substring(start, i + 1);
                }
            }
        }
        return null;
    }
}
