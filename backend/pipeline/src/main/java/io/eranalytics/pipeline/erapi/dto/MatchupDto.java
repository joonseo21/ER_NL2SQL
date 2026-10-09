package io.eranalytics.pipeline.erapi.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.eranalytics.pipeline.erapi.jackson.CountMapDeserializer;
import java.util.Map;

public record MatchupDto(
        @JsonDeserialize(using = CountMapDeserializer.class) Map<Integer, Integer> killDetails,
        @JsonDeserialize(using = CountMapDeserializer.class) Map<Integer, Integer> deathDetails
) {
}
