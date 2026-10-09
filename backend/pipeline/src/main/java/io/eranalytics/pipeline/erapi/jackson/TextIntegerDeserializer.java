package io.eranalytics.pipeline.erapi.jackson;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import java.io.IOException;

public final class TextIntegerDeserializer extends JsonDeserializer<Integer> {
    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        try {
            return BattleJsonValues.textInteger(parser.readValueAsTree(), "value");
        } catch (IllegalArgumentException exception) {
            throw JsonMappingException.from(parser, "Invalid text integer");
        }
    }
}
