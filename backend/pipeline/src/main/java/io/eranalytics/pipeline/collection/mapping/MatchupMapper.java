package io.eranalytics.pipeline.collection.mapping;

import static io.eranalytics.pipeline.erapi.jackson.BattleJsonValues.invalid;

import io.eranalytics.pipeline.collection.model.MatchupRow;
import io.eranalytics.pipeline.erapi.dto.MatchupDto;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class MatchupMapper {
    private MatchupMapper() {
    }

    public static List<MatchupRow> map(Integer character, MatchupDto input) {
        Map<Integer, Integer> kills = input.killDetails() == null ? Map.of() : input.killDetails();
        Map<Integer, Integer> deaths = input.deathDetails() == null ? Map.of() : input.deathDetails();
        var opponents = new LinkedHashSet<>(kills.keySet());
        opponents.addAll(deaths.keySet());
        if (character == null && !opponents.isEmpty()) {
            throw invalid("characterNum");
        }
        return opponents.stream().sorted().map(opponent -> new MatchupRow(
                character, opponent, kills.getOrDefault(opponent, 0), deaths.getOrDefault(opponent, 0))).toList();
    }
}
