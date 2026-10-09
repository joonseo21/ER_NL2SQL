package io.eranalytics.pipeline.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.eranalytics.pipeline.erapi.jackson.BattleTimeDeserializer;
import io.eranalytics.pipeline.erapi.jackson.StrictScalarDeserializers;
import java.time.OffsetDateTime;

/** Dedicated to battle DTOs; leaves Spring's HTTP/JSON mapper settings unchanged. */
public final class BattleResultObjectMapperFactory {
    private BattleResultObjectMapperFactory() {
    }

    public static ObjectMapper create() {
        SimpleModule module = new SimpleModule("battle-result-values");
        module.addDeserializer(Integer.class, new StrictScalarDeserializers.IntegerValue());
        module.addDeserializer(Long.class, new StrictScalarDeserializers.LongValue());
        module.addDeserializer(Short.class, new StrictScalarDeserializers.ShortValue());
        module.addDeserializer(Double.class, new StrictScalarDeserializers.DoubleValue());
        module.addDeserializer(Boolean.class, new StrictScalarDeserializers.BooleanValue());
        module.addDeserializer(String.class, new StrictScalarDeserializers.StringValue());
        module.addDeserializer(OffsetDateTime.class, new BattleTimeDeserializer());
        module.addKeyDeserializer(Integer.class, new StrictScalarDeserializers.IntegerKey());
        module.addKeyDeserializer(Short.class, new StrictScalarDeserializers.ShortKey());
        return JsonMapper.builder()
                .addModule(module)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }
}
