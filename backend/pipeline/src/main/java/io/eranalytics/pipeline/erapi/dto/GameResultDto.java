package io.eranalytics.pipeline.erapi.dto;

import java.time.OffsetDateTime;

public record GameResultDto(
        Long gameId,
        Integer seasonId,
        Integer matchingMode,
        Integer matchingTeamMode,
        Integer versionSeason,
        Integer versionMajor,
        Integer versionMinor,
        Integer matchSize,
        Integer mmrAvg,
        Integer mainWeather,
        Integer subWeather,
        OffsetDateTime startDtm,
        String serverName
) {
}
