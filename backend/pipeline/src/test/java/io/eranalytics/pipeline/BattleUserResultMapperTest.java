package io.eranalytics.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class BattleUserResultMapperTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mapsNullableMmrAndCoreFields() throws Exception {
        JsonNode node = objectMapper.readTree("""
                {"gameId": 42, "nickname": "masked", "characterNum": 23,
                 "gameRank": 1, "matchingMode": 3, "matchingTeamMode": 3,
                 "startDtm": "2026-09-24T10:15:30"}
                """);

        var participant = BattleUserResultMapper.participant(node);
        var game = BattleUserResultMapper.game(node);

        assertThat(participant.gameId()).isEqualTo(42);
        assertThat(participant.mmrBefore()).isNull();
        assertThat(game.matchingTeamMode()).isEqualTo(3);
        assertThat(game.startDtm()).isNotNull();
    }
}
