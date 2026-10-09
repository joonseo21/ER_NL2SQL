package io.eranalytics.pipeline.erapi.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.eranalytics.pipeline.erapi.jackson.SkillOrderDeserializer;
import io.eranalytics.pipeline.erapi.jackson.TextIntegerDeserializer;
import java.util.List;

public record LoadoutDto(
        Integer traitFirstCore,
        Integer tacticalSkillGroup,
        Integer tacticalSkillLevel,
        Integer routeIdOfStart,
        JsonNode skillLevelInfo,
        JsonNode creditSource,
        JsonNode killMonsters,
        @JsonDeserialize(using = TextIntegerDeserializer.class) Integer placeOfStart,
        @JsonProperty("skillOrderInfo") @JsonDeserialize(using = SkillOrderDeserializer.class) List<Integer> skillOrder
) {
}
