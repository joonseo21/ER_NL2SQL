package io.eranalytics.pipeline.erapi.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UserGamesPage(List<UserGameSummary> userGames, Long next) {
    public UserGamesPage {
        if (userGames == null || userGames.stream().anyMatch(java.util.Objects::isNull)
                || (next != null && next <= 0)) {
            throw new IllegalArgumentException("Invalid user games page");
        }
        userGames = List.copyOf(userGames);
    }
}
