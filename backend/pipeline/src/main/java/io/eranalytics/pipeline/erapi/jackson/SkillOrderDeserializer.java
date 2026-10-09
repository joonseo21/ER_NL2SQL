package io.eranalytics.pipeline.erapi.jackson;

import static io.eranalytics.pipeline.erapi.jackson.BattleJsonValues.*;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class SkillOrderDeserializer extends JsonDeserializer<List<Integer>> {
    @Override
    public List<Integer> deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        try {
            return skillOrder(parser.readValueAsTree());
        } catch (IllegalArgumentException exception) {
            throw JsonMappingException.from(parser, "Invalid skill order");
        }
    }

    private static List<Integer> skillOrder(JsonNode node) {
        JsonNode order = object(node, "skillOrderInfo");
        if (order == null) {
            return null;
        }
        Map<Integer, Integer> entries = new TreeMap<>();
        order.properties().forEach(entry -> {
            int position = integerKey(entry.getKey(), "skillOrderInfo");
            if (entries.containsKey(position)) {
                throw invalid("skillOrderInfo");
            }
            entries.put(position, integer(entry.getValue(), "skillOrderInfo"));
        });
        return Collections.unmodifiableList(new ArrayList<>(entries.values()));
    }

}
