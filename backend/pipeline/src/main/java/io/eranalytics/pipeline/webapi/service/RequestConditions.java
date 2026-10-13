package io.eranalytics.pipeline.webapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.HashSet;
import java.util.Set;
import org.springframework.http.HttpStatus;

/** Checks request shape only; the agent decides whether data supports a condition. */
public final class RequestConditions {
    private RequestConditions() {}

    public static JsonNode validate(JsonNode conditions) {
        if (conditions == null) return JsonNodeFactory.instance.objectNode();
        object(conditions, Set.of("patch", "tier", "tier_division", "character_code"));
        JsonNode tier = conditions.get("tier");
        if (tier != null && !tier.isNull() && !tier.isTextual()) fail();
        integer(conditions.get("tier_division"));
        integer(conditions.get("character_code"));
        if (conditions.hasNonNull("tier_division")
                && (tier == null || tier.isNull() || tier.asText().isBlank())) fail();
        if (conditions.has("patch")) patch(conditions.get("patch"));
        return conditions.deepCopy();
    }

    private static void patch(JsonNode patch) {
        object(patch, Set.of("mode", "versions"));
        String mode = patch.has("mode") ? patch.get("mode").asText() : "latest";
        if (!Set.of("latest", "exact", "all").contains(mode)) fail();
        JsonNode versions =
                patch.has("versions")
                        ? patch.get("versions")
                        : JsonNodeFactory.instance.arrayNode();
        if (!versions.isArray()) fail();
        if ("exact".equals(mode) == versions.isEmpty()) fail();
        Set<JsonNode> seen = new HashSet<>();
        for (JsonNode version : versions) {
            object(version, Set.of("major", "minor"));
            requiredInteger(version.get("major"));
            requiredInteger(version.get("minor"));
            if (!seen.add(version)) fail();
        }
    }

    private static void object(JsonNode node, Set<String> fields) {
        if (!node.isObject()) fail();
        node.fieldNames()
                .forEachRemaining(
                        name -> {
                            if (!fields.contains(name)) fail();
                        });
    }

    private static void integer(JsonNode node) {
        if (node != null && !node.isNull()) requiredInteger(node);
    }

    private static void requiredInteger(JsonNode node) {
        if (node == null
                || !node.isIntegralNumber()
                || !node.canConvertToInt()
                || node.intValue() < 0) fail();
    }

    private static void fail() {
        throw new WebApiException(
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "질문 조건의 형식을 확인해 주세요.");
    }
}
