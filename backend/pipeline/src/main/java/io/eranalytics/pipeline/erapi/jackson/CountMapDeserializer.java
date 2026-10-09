package io.eranalytics.pipeline.erapi.jackson;

import static io.eranalytics.pipeline.erapi.jackson.BattleJsonValues.*;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.ObjectCodec;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public final class CountMapDeserializer extends JsonDeserializer<Map<Integer, Integer>> {
    @Override
    public Map<Integer, Integer> deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        try {
            return countMap(parser.readValueAsTree(), "value", parser.getCodec());
        } catch (IllegalArgumentException exception) {
            throw JsonMappingException.from(parser, "Invalid matchup counts");
        }
    }

    private static Map<Integer, Integer> countMap(JsonNode node, String key, ObjectCodec json) {
        if (!present(node) || node.isTextual() && node.textValue().isEmpty()) {
            return Map.of();
        }
        JsonNode parsed = node;
        if (node.isTextual()) {
            try (JsonParser embedded = json.getFactory().createParser(node.textValue())) {
                parsed = json.readTree(embedded);
            } catch (IOException exception) {
                throw invalid(key);
            }
        }
        requireObject(parsed, key);
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        parsed.properties().forEach(entry -> {
            int code = integerKey(entry.getKey(), key);
            int count = requiredInteger(entry.getValue(), key);
            // V4 reconstructs only nonzero matchup counts. A zero-valued key would be lost.
            if (count == 0 || counts.putIfAbsent(code, count) != null) {
                throw invalid(key);
            }
        });
        return counts;
    }

}
