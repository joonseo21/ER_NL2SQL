package io.eranalytics.pipeline.webapi.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.eranalytics.pipeline.webapi.service.QuestionFailure;
import java.util.Set;

/** Keeps decimals and evidence as supplied; rejects malformed snapshots before database writes. */
public final class AgentResponseValidator {
    private AgentResponseValidator() {}

    public static ObjectNode validate(JsonNode response) {
        if (!(response instanceof ObjectNode)) fail();
        ObjectNode object = (ObjectNode) response;
        nonblank(object.get("kind"));
        nonblank(object.get("log_id"));
        nonblank(object.get("question"));
        if (!Set.of("answer", "notice", "clarification").contains(object.path("kind").asText())) {
            fail();
        }
        elapsed(object.get("elapsed_ms"));
        arrays(object);
        for (String field : Set.of("sql", "summary", "message")) nullableText(object.get(field));
        nullableObject(object.get("applied_conditions"));
        nullableObject(object.get("evidence"));
        content(object);
        return object;
    }

    private static void elapsed(JsonNode elapsed) {
        if (elapsed == null
                || !elapsed.isIntegralNumber()
                || !elapsed.canConvertToLong()
                || elapsed.longValue() < 0) fail();
    }

    private static void arrays(ObjectNode object) {
        for (String field : Set.of("columns", "rows", "warnings", "examples", "clarifications")) {
            if (!object.path(field).isArray()) fail();
        }
        if (object.path("rows").size() > 200) fail();
        for (JsonNode row : object.path("rows")) {
            if (!row.isObject()) fail();
        }
    }

    private static void content(ObjectNode object) {
        if ("answer".equals(object.path("kind").asText())) {
            nonblank(object.get("sql"));
            if (!object.path("applied_conditions").isObject()) fail();
        } else {
            nonblank(object.get("message"));
            if (!object.path("sql").isNull()
                    || !object.path("summary").isNull()
                    || !object.path("rows").isEmpty()
                    || !object.path("columns").isEmpty()) fail();
        }
    }

    private static void nonblank(JsonNode value) {
        if (value == null || !value.isTextual() || value.textValue().isBlank()) fail();
    }

    private static void nullableText(JsonNode value) {
        if (value == null || (!value.isNull() && !value.isTextual())) fail();
    }

    private static void nullableObject(JsonNode value) {
        if (value == null || (!value.isNull() && !value.isObject())) fail();
    }

    private static void fail() {
        throw QuestionFailure.AGENT_INVALID_RESPONSE.exception();
    }
}
