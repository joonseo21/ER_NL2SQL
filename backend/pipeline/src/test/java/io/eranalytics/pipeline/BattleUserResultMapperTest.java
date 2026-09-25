package io.eranalytics.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BattleUserResultMapperTest {
    private static final Instant KST_2026_09_25_05_10_50_050 = Instant.parse("2026-09-24T20:10:50.050Z");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mapsNullableMmrAndCoreFields() throws Exception {
        JsonNode node = objectMapper.readTree("""
                {"gameId": 42, "nickname": "masked", "characterNum": 23,
                 "gameRank": 1, "matchingMode": 3, "matchingTeamMode": 3,
                 "startDtm": "2026-09-25T05:10:50.050+0900"}
                """);

        var participant = BattleUserResultMapper.participant(node);
        var game = BattleUserResultMapper.game(node);

        assertThat(participant.gameId()).isEqualTo(42);
        assertThat(participant.mmrBefore()).isNull();
        assertThat(game.matchingTeamMode()).isEqualTo(3);
        assertThat(game.startDtm()).isNotNull();
    }

    @Test
    void parsesOffsetWithoutColon() throws Exception {
        var game = BattleUserResultMapper.game(gameWithStartDtm("\"2026-09-25T05:10:50.050+0900\""));

        assertThat(game.startDtm().toInstant()).isEqualTo(KST_2026_09_25_05_10_50_050);
    }

    @Test
    void parsesOffsetWithColon() throws Exception {
        var game = BattleUserResultMapper.game(gameWithStartDtm("\"2026-09-25T05:10:50.050+09:00\""));

        assertThat(game.startDtm().toInstant()).isEqualTo(KST_2026_09_25_05_10_50_050);
    }

    @Test
    void bothOffsetFormsYieldTheSameInstant() throws Exception {
        var compact = BattleUserResultMapper.game(gameWithStartDtm("\"2026-09-25T05:10:50.050+0900\""));
        var colon = BattleUserResultMapper.game(gameWithStartDtm("\"2026-09-25T05:10:50.050+09:00\""));

        assertThat(compact.startDtm()).isEqualTo(colon.startDtm());
    }

    @Test
    void parsesHourOnlyOffset() throws Exception {
        var game = BattleUserResultMapper.game(gameWithStartDtm("\"2026-09-25T05:10:50.050+09\""));

        assertThat(game.startDtm().toInstant()).isEqualTo(KST_2026_09_25_05_10_50_050);
    }

    @Test
    void parsesNegativeOffsetAndZulu() throws Exception {
        var negative = BattleUserResultMapper.game(gameWithStartDtm("\"2026-09-24T15:10:50-0500\""));
        var zulu = BattleUserResultMapper.game(gameWithStartDtm("\"2026-09-24T20:10:50Z\""));

        assertThat(negative.startDtm().toInstant()).isEqualTo(Instant.parse("2026-09-24T20:10:50Z"));
        assertThat(zulu.startDtm().toInstant()).isEqualTo(Instant.parse("2026-09-24T20:10:50Z"));
    }

    @Test
    void missingStartDtmIsNull() throws Exception {
        JsonNode node = objectMapper.readTree("{\"gameId\": 42, \"nickname\": \"masked\"}");

        assertThat(BattleUserResultMapper.game(node).startDtm()).isNull();
    }

    @Test
    void explicitNullStartDtmIsNull() throws Exception {
        var game = BattleUserResultMapper.game(gameWithStartDtm("null"));

        assertThat(game.startDtm()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"\"",
            "\"not-a-date\"",
            "\"2026-13-45T05:10:50.050+0900\"",
            "\"2026-09-25T25:10:50+0900\"",
            "\"2026-09-25T05:10:50.050+9\"",
            "\"2026-09-25\"",
            "\"20260925T051050+0900\"",
            "20260925",
            "true"
    })
    void malformedStartDtmIsNullAndDoesNotThrow(String jsonValue) throws Exception {
        var game = BattleUserResultMapper.game(gameWithStartDtm(jsonValue));

        assertThat(game.startDtm()).isNull();
    }

    @Test
    void startDtmWithoutOffsetIsNullBecauseZoneIsUnknown() throws Exception {
        var game = BattleUserResultMapper.game(gameWithStartDtm("\"2026-09-24T10:15:30\""));

        assertThat(game.startDtm()).isNull();
    }

    @Test
    void malformedStartDtmKeepsOtherGameFields() throws Exception {
        var game = BattleUserResultMapper.game(gameWithStartDtm("\"garbage\""));

        assertThat(game.gameId()).isEqualTo(42);
        assertThat(game.matchingTeamMode()).isEqualTo(3);
    }

    private JsonNode gameWithStartDtm(String jsonValue) throws Exception {
        return objectMapper.readTree("""
                {"gameId": 42, "nickname": "masked", "matchingMode": 3,
                 "matchingTeamMode": 3, "startDtm": %s}
                """.formatted(jsonValue));
    }
}
