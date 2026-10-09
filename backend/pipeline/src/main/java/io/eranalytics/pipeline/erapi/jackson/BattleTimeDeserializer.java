package io.eranalytics.pipeline.erapi.jackson;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

public final class BattleTimeDeserializer extends JsonDeserializer<OffsetDateTime> {
    private static final Pattern COMPACT_OFFSET = Pattern.compile("(?<=\\d)([+-]\\d{2})(\\d{2})$");

    @Override
    public OffsetDateTime deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        try {
            String value = BattleJsonValues.text(parser.readValueAsTree(), "startDtm");
            return OffsetDateTime.parse(COMPACT_OFFSET.matcher(value.trim()).replaceFirst("$1:$2"))
                    .toInstant().atOffset(ZoneOffset.UTC);
        } catch (IllegalArgumentException | DateTimeParseException exception) {
            throw JsonMappingException.from(parser, "Invalid battle timestamp");
        }
    }
}
