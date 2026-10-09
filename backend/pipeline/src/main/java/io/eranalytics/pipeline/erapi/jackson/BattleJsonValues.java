package io.eranalytics.pipeline.erapi.jackson;

import com.fasterxml.jackson.databind.JsonNode;

/** Value checks shared by the battle DTO deserializers. Errors contain no raw values. */
public final class BattleJsonValues {
    private BattleJsonValues() {
    }

    public static Integer integer(JsonNode node, String key) {
        if (!present(node)) {
            return null;
        }
        if (!node.isNumber()) {
            throw invalid(key);
        }
        try {
            // A fractional/out-of-range value fails V4's value verification after its SQL cast.
            return node.decimalValue().intValueExact();
        } catch (ArithmeticException exception) {
            throw invalid(key);
        }
    }

    public static int requiredInteger(JsonNode node, String key) {
        Integer value = integer(node, key);
        if (value == null) {
            throw invalid(key);
        }
        return value;
    }

    public static Double decimal(JsonNode node, String key) {
        if (!present(node)) {
            return null;
        }
        if (!node.isNumber() || !Double.isFinite(node.doubleValue())) {
            throw invalid(key);
        }
        return node.doubleValue();
    }

    public static Boolean bool(JsonNode node, String key) {
        if (!present(node)) {
            return null;
        }
        if (!node.isBoolean()) {
            throw invalid(key);
        }
        return node.booleanValue();
    }

    public static String text(JsonNode node, String key) {
        if (!present(node)) {
            return null;
        }
        if (!node.isTextual()) {
            throw invalid(key);
        }
        return node.textValue();
    }

    public static Integer textInteger(JsonNode node, String key) {
        String value = text(node, key);
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException exception) {
            throw invalid(key);
        }
    }

    public static int integerKey(String value, String key) {
        try {
            int parsed = Integer.parseInt(value);
            if (!Integer.toString(parsed).equals(value)) {
                throw invalid(key);
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw invalid(key);
        }
    }

    public static short smallintKey(String value, String key) {
        int parsed = integerKey(value, key);
        if (parsed < Short.MIN_VALUE || parsed > Short.MAX_VALUE) {
            throw invalid(key);
        }
        return (short) parsed;
    }

    public static Short smallint(JsonNode node, String key) {
        Integer value = integer(node, key);
        if (value == null) {
            return null;
        }
        if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
            throw invalid(key);
        }
        return value.shortValue();
    }

    public static JsonNode object(JsonNode node, String key) {
        if (!present(node)) {
            return null;
        }
        requireObject(node, key);
        return node;
    }

    public static void requireObject(JsonNode node, String key) {
        if (node == null || !node.isObject()) {
            throw invalid(key);
        }
    }

    public static boolean present(JsonNode node) {
        return node != null && !node.isNull();
    }

    public static String emptyToNull(String value) {
        return value != null && value.isEmpty() ? null : value;
    }

    public static IllegalArgumentException invalid(String key) {
        // Never include raw values, especially nicknames, in collector errors.
        return new IllegalArgumentException("Missing, invalid or non-reconstructable field: " + key);
    }

}
