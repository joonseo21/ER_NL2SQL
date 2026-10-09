package io.eranalytics.pipeline.erapi.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.eranalytics.pipeline.erapi.jackson.TextIntegerDeserializer;

public record DeathDto(
        String killer,
        String killerCharacter,
        String killerWeapon,
        String causeOfDeath,
        @JsonDeserialize(using = TextIntegerDeserializer.class) Integer placeOfDeath,
        String killDetail
) {
}
