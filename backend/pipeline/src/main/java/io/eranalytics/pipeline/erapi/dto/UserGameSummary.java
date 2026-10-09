package io.eranalytics.pipeline.erapi.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UserGameSummary(Long gameId, Integer seasonId, Integer matchingMode,
                              Integer matchingTeamMode, Integer versionMajor) {
    public UserGameSummary {
        if (gameId == null || gameId <= 0 || seasonId == null || matchingMode == null
                || matchingTeamMode == null || versionMajor == null) {
            throw new IllegalArgumentException("User game summary is missing required fields");
        }
    }
}
