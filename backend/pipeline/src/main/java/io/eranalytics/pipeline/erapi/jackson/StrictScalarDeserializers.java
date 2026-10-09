package io.eranalytics.pipeline.erapi.jackson;

import static io.eranalytics.pipeline.erapi.jackson.BattleJsonValues.*;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.KeyDeserializer;
import java.io.IOException;

/** Jackson's usual number/string coercions would silently change V4 values. */
public final class StrictScalarDeserializers {
    private StrictScalarDeserializers() {
    }

    private abstract static class Scalar<T> extends JsonDeserializer<T> {
        abstract T value(JsonNode node);

        @Override
        public T deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonNode node = parser.readValueAsTree();
            try {
                return value(node);
            } catch (IllegalArgumentException exception) {
                throw JsonMappingException.from(parser, "Invalid scalar value");
            }
        }
    }

    public static final class IntegerValue extends Scalar<Integer> {
        @Override Integer value(JsonNode node) {
            return integer(node, "value");
        }
    }

    public static final class LongValue extends Scalar<Long> {
        @Override Long value(JsonNode node) {
            if (!node.isNumber()) {
                throw invalid("value");
            }
            try {
                return node.decimalValue().longValueExact();
            } catch (ArithmeticException exception) {
                throw invalid("value");
            }
        }
    }

    public static final class ShortValue extends Scalar<Short> {
        @Override Short value(JsonNode node) {
            return smallint(node, "value");
        }
    }

    public static final class DoubleValue extends Scalar<Double> {
        @Override Double value(JsonNode node) {
            return decimal(node, "value");
        }
    }

    public static final class BooleanValue extends Scalar<Boolean> {
        @Override Boolean value(JsonNode node) {
            return bool(node, "value");
        }
    }

    public static final class StringValue extends Scalar<String> {
        @Override String value(JsonNode node) {
            return text(node, "value");
        }
    }

    public static final class IntegerKey extends KeyDeserializer {
        @Override
        public Integer deserializeKey(String key, DeserializationContext context) throws IOException {
            try {
                return integerKey(key, "key");
            } catch (IllegalArgumentException exception) {
                throw JsonMappingException.from(context.getParser(), "Invalid integer key");
            }
        }
    }

    public static final class ShortKey extends KeyDeserializer {
        @Override
        public Short deserializeKey(String key, DeserializationContext context) throws IOException {
            try {
                return smallintKey(key, "key");
            } catch (IllegalArgumentException exception) {
                throw JsonMappingException.from(context.getParser(), "Invalid smallint key");
            }
        }
    }
}
