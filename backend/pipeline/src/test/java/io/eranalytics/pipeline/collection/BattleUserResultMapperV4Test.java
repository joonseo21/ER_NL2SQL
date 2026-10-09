package io.eranalytics.pipeline.collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.eranalytics.pipeline.collection.mapping.LegacyBattleUserResultMapper;
import io.eranalytics.pipeline.collection.model.DeathRow;
import io.eranalytics.pipeline.collection.model.EquipmentRow;
import io.eranalytics.pipeline.collection.model.MappedMatch;
import io.eranalytics.pipeline.collection.model.MasteryRow;
import io.eranalytics.pipeline.collection.model.MatchupRow;
import io.eranalytics.pipeline.collection.model.TraitRow;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class BattleUserResultMapperV4Test {
    private final ObjectMapper json = new ObjectMapper();
    private final BattleUserResultMapper mapper = new BattleUserResultMapper();

    @Test
    void mapsEveryPromotedNumericColumnAgainstV4Contract() throws Exception {
        List<JsonNode> results = fixture();
        var match = map(results);
        var columns = StoredColumnAssertions.participant(match.participants().getFirst().data());
        Matcher integers = Pattern.compile(
                "(?m)^\\s*(\\w+) = \\(s\\.\"([^\"]+)\"\\)::integer,").matcher(v4());
        int integerCount = 0;
        while (integers.find()) {
            assertThat(columns.get(integers.group(1))).as(integers.group(2))
                    .isEqualTo(results.getFirst().get(integers.group(2)).intValue());
            integerCount++;
        }
        assertThat(integerCount).isEqualTo(89);

        Matcher doubles = Pattern.compile(
                "(?m)^\\s*(\\w+) = \\(s\\.\"([^\"]+)\"\\)::double precision,").matcher(v4());
        int doubleCount = 0;
        while (doubles.find()) {
            assertThat(columns.get(doubles.group(1))).as(doubles.group(2))
                    .isEqualTo(results.getFirst().get(doubles.group(2)).doubleValue());
            doubleCount++;
        }
        assertThat(doubleCount).isEqualTo(8);
        assertThat(columns)
                .containsEntry("game_id", 410001L)
                .containsEntry("nickname", "synthetic_alpha")
                .containsEntry("character_num", 23)
                .containsEntry("mmr_before", 3950)
                .containsEntry("version_major", 5)
                .containsEntry("version_minor", 0)
                .containsEntry("place_of_start", 70)
                .containsEntry("give_up", 0)
                .containsEntry("gambit", false)
                .containsEntry("kings_gambit", true)
                .containsEntry("kill_gamma", false);
    }

    @Test
    void mapsGameFieldsAndKeepsDurationAtParticipantLevel() throws Exception {
        List<JsonNode> results = fixture();
        ((ObjectNode) results.get(1)).put("duration", 600);
        var match = map(results);
        assertThat(StoredColumnAssertions.game(match.game())).containsEntry("team_count", 2)
                .containsEntry("match_size", 24).containsEntry("mmr_avg", 4500)
                .containsEntry("main_weather", 2).containsEntry("sub_weather", 0)
                .doesNotContainKey("duration");
        assertThat(((OffsetDateTime) StoredColumnAssertions.game(match.game()).get("start_dtm")).toInstant())
                .isEqualTo(Instant.parse("2026-10-08T12:10:50.050Z"));
        assertThat(StoredColumnAssertions.participant(match.participants().get(0).data())).containsEntry("duration", 1080);
        assertThat(StoredColumnAssertions.participant(match.participants().get(1).data())).containsEntry("duration", 600);
    }

    @Test
    void countsDistinctNonNullTeamsLikePostgres() throws Exception {
        List<JsonNode> results = fixture();
        ((ObjectNode) results.get(2)).putNull("teamNumber");
        assertThat(StoredColumnAssertions.game(map(results).game())).containsEntry("team_count", 1);
    }

    @Test
    void retainsArrayPositionsNullElementsAndEmptyArrays() throws Exception {
        var columns = StoredColumnAssertions.participant(map(fixture()).participants().getFirst().data());
        assertThat(columns.get("total_vf_credits")).isEqualTo(Arrays.asList(10, null, 30));
        assertThat(columns.get("used_vf_credits")).isEqualTo(List.of(1, 2, 3));
        assertThat(columns.get("total_tk_per_min")).isEqualTo(List.of(0, 1, 2));
        assertThat(columns.get("food_craft_count")).isEqualTo(List.of(2, 0, 5));
        assertThat(columns.get("item_transferred_drone")).isEqualTo(List.of(110001, 110002));
        assertThat(columns.get("item_transferred_console")).isEqualTo(List.of());
        assertThat(columns.get("skill_order")).isEqualTo(List.of(1001, 1002, 1003));
    }

    @Test
    void preservesJsonMapsWithoutFlatteningOrAliasingInput() throws Exception {
        List<JsonNode> results = fixture();
        var columns = StoredColumnAssertions.participant(map(results).participants().getFirst().data());
        for (var mapping : Map.of("skill_level_info", "skillLevelInfo",
                "credit_source", "creditSource", "kill_monsters", "killMonsters").entrySet()) {
            assertThat(columns.get(mapping.getKey())).isEqualTo(results.getFirst().get(mapping.getValue()));
            assertThat(columns.get(mapping.getKey())).isNotSameAs(results.getFirst().get(mapping.getValue()));
        }
    }

    @Test
    void mapsFinalAndFirstEquipmentWithIndependentKindsAndGrades() throws Exception {
        var equipment = map(fixture()).participants().getFirst().equipment();
        assertThat(equipment).containsExactlyInAnyOrder(
                new EquipmentRow("FINAL", (short) 0, 110003, (short) 4),
                new EquipmentRow("FINAL", (short) 1, 210003, (short) 5),
                new EquipmentRow("FIRST", (short) 0, 110002, null),
                new EquipmentRow("FIRST", (short) 0, 110001, null),
                new EquipmentRow("FIRST", (short) 1, 210001, null));
    }

    @Test
    void mapsAllTraitGroupsAndMasteryCodes() throws Exception {
        var participant = map(fixture()).participants().getFirst();
        assertThat(participant.traits()).containsExactlyInAnyOrder(
                new TraitRow("CORE", 700101),
                new TraitRow("FIRST_SUB", 710102),
                new TraitRow("FIRST_SUB", 710101),
                new TraitRow("SECOND_SUB", 720103),
                new TraitRow("SECOND_SUB", 720101));
        assertThat(participant.mastery()).containsExactlyInAnyOrder(
                new MasteryRow(16, 20),
                new MasteryRow(101, 8));
    }

    @Test
    void mergesStringEncodedKillsAndDeathsByOpponentCharacter() throws Exception {
        assertThat(map(fixture()).participants().getFirst().matchups()).containsExactly(
                new MatchupRow(23, 12, 2, 1),
                new MatchupRow(23, 24, 1, 0),
                new MatchupRow(23, 31, 0, 1));
    }

    @Test
    void resolvesDeathsWithinMatchThenFallsBackToCaseInsensitiveMetadata() throws Exception {
        var deaths = map(fixture()).participants().getFirst().deaths();
        assertThat(deaths).containsExactly(
                new DeathRow((short) 1, "player", 1, 12,
                        "SyntheticWrongName", "16", "synthetic_skill", 80),
                new DeathRow((short) 2, "player", null, 137,
                        "sYnThEtIcRaNgEr", null, null, null),
                new DeathRow((short) 3, "wildAnimal", null, null,
                        "Boar", null, null, 90));
    }

    @Test
    void unresolvedKillerDoesNotFabricateParticipantOrCharacter() throws Exception {
        var deaths = mapper.mapMatch(fixture(), Map.of()).participants().getFirst().deaths();
        assertThat(deaths.get(1).killerParticipantIndex()).isNull();
        assertThat(deaths.get(1).killerCharacterNum()).isNull();
    }

    @Test
    void removesExactlyTheV4KeysAndRetainsSensitiveAndUnknownResidualKeys() throws Exception {
        String sql = v4();
        Matcher removal = Pattern.compile("SET raw = p\\.raw - ARRAY\\[(.*?)\\]", Pattern.DOTALL).matcher(sql);
        assertThat(removal.find()).isTrue();
        Set<String> expected = new LinkedHashSet<>();
        Matcher key = Pattern.compile("'([^']+)'").matcher(removal.group(1));
        while (key.find()) {
            expected.add(key.group(1));
        }
        assertThat(expected).hasSize(161);
        assertThat(mapper.movedRawKeys()).containsExactlyInAnyOrderElementsOf(expected);
        List<JsonNode> results = fixture();
        ObjectNode original = (ObjectNode) results.getFirst();
        for (String removedKey : expected) {
            assertThat(original.has(removedKey)).as("fixture covers %s", removedKey).isTrue();
        }
        ObjectNode expectedResidual = original.deepCopy();
        expectedResidual.remove(expected);
        assertThat(map(results).participants().getFirst().remainingRaw()).isEqualTo(expectedResidual);
        assertThat(expectedResidual.has("killDetail")).isTrue();
        assertThat(expectedResidual.has("killDetail2")).isTrue();
        assertThat(expectedResidual.has("killDetail3")).isTrue();
        assertThat(expectedResidual.has("crGetHunt")).isTrue();
        assertThat(expectedResidual.has("experimentalFutureField")).isTrue();
        assertThat(expectedResidual.has("characterNum")).isFalse();
    }

    @Test
    void mappingDoesNotMutateInputAndLegacyWriterStillReceivesFullRaw() throws Exception {
        List<JsonNode> results = fixture();
        JsonNode before = results.getFirst().deepCopy();
        var mapped = map(results);
        assertThat(results.getFirst()).isEqualTo(before);
        assertThat(LegacyBattleUserResultMapper.participant(results.getFirst()).raw()).isSameAs(results.getFirst());
        mapped.participants().getFirst().remainingRaw().remove("experimentalFutureField");
        assertThat(results.getFirst()).isEqualTo(before);
    }

    @Test
    void acceptsMissingAndNullOptionalValuesWithoutInventingZerosOrChildRows() throws Exception {
        JsonNode minimal = json.readTree("""
                {"gameId": 42, "nickname": "synthetic", "victory": null, "gambit": null,
                 "totalVFCredits": null, "skillLevelInfo": null, "placeOfStart": ""}
                """);
        var match = mapper.mapMatch(List.of(minimal), Map.of());
        var participant = match.participants().getFirst();
        assertThat(StoredColumnAssertions.participant(participant.data())).containsEntry("victory", null)
                .containsEntry("gambit", null).containsEntry("total_vf_credits", null)
                .containsEntry("skill_level_info", null).containsEntry("place_of_start", null)
                .containsEntry("skill_order", null).containsEntry("mmr_before", null);
        assertThat(participant.equipment()).isEmpty();
        assertThat(participant.traits()).isEmpty();
        assertThat(participant.mastery()).isEmpty();
        assertThat(participant.matchups()).isEmpty();
        assertThat(participant.deaths()).isEmpty();
        assertThat(StoredColumnAssertions.game(match.game())).containsEntry("team_count", 0).containsEntry("start_dtm", null);
    }

    @Test
    void acceptsEmptyContainersButKeepsArrayAndJsonContainerTypes() throws Exception {
        ObjectNode minimal = (ObjectNode) json.readTree("""
                {"gameId":42,"nickname":"synthetic","equipment":{},"equipmentGrade":{},
                 "equipFirstItemForLog":{},"traitFirstSub":[],"traitSecondSub":[],
                 "masteryLevel":{},"killDetails":"","deathDetails":"{}",
                 "skillOrderInfo":{},"totalVFCredits":[],"skillLevelInfo":{}}
                """);
        var participant = mapper.mapMatch(List.of(minimal), Map.of()).participants().getFirst();
        assertThat(StoredColumnAssertions.participant(participant.data())).containsEntry("skill_order", List.of())
                .containsEntry("total_vf_credits", List.of());
        assertThat(StoredColumnAssertions.participant(participant.data()).get("skill_level_info")).isEqualTo(json.readTree("{}"));
        assertThat(participant.matchups()).isEmpty();
    }

    @Test
    void acceptsIntegralDecimalsThatPassV4NumericEquality() throws Exception {
        List<JsonNode> results = fixture();
        ((ObjectNode) results.getFirst()).set("victory", json.readTree("1.0"));
        assertThat(StoredColumnAssertions.participant(map(results).participants().getFirst().data())).containsEntry("victory", 1);
    }

    @Test
    void acceptsIntegralDecimalGameIdAndObjectEncodedMatchupCounts() throws Exception {
        List<JsonNode> results = fixture();
        for (JsonNode result : results) {
            ((ObjectNode) result).set("gameId", json.readTree("410001.0"));
        }
        ObjectNode first = (ObjectNode) results.getFirst();
        first.set("killDetails", json.readTree("{\"12\":2,\"24\":1}"));
        first.set("deathDetails", json.readTree("{\"12\":1,\"31\":1}"));
        var match = map(results);
        assertThat(StoredColumnAssertions.game(match.game())).containsEntry("game_id", 410001L);
        assertThat(match.participants().getFirst().matchups()).containsExactly(
                new MatchupRow(23, 12, 2, 1), new MatchupRow(23, 24, 1, 0),
                new MatchupRow(23, 31, 0, 1));
    }

    @Test
    void preservesNullableEquipmentGradesAndDoesNotInterpretNonPlayerKillDetail() throws Exception {
        List<JsonNode> results = fixture();
        ObjectNode first = (ObjectNode) results.getFirst();
        ((ObjectNode) first.get("equipmentGrade")).putNull("0");
        first.put("killDetail3", 999);
        var participant = map(results).participants().getFirst();
        assertThat(participant.equipment()).contains(new EquipmentRow("FINAL", (short) 0, 110003, null));
        assertThat(participant.deaths().get(2).killerParticipantIndex()).isNull();
        assertThat(participant.remainingRaw().get("killDetail3").intValue()).isEqualTo(999);
    }

    @Test
    void invalidMapKeyIsNotLeakedThroughJacksonException() throws Exception {
        List<JsonNode> results = fixture();
        ((ObjectNode) results.getFirst()).set("masteryLevel",
                json.readTree("{\"private_map_key\":1}"));
        assertThatThrownBy(() -> map(results)).hasMessageContaining("masteryLevel")
                .hasMessageNotContaining("private_map_key").hasNoCause();
    }

    @Test
    void validatesSharedGameFieldsAfterTimezoneNormalization() throws Exception {
        List<JsonNode> results = fixture();
        ((ObjectNode) results.get(1)).put("startDtm", "2026-10-08T12:10:50.050Z");
        assertThat(map(results).participants()).hasSize(3);
        ((ObjectNode) results.get(1)).put("seasonId", 42);
        assertThatThrownBy(() -> map(results)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shared game fields");
    }

    @Test
    void refusesDuplicateParticipantsAndMixedGameIds() throws Exception {
        List<JsonNode> results = fixture();
        ((ObjectNode) results.get(1)).put("nickname", "synthetic_alpha");
        assertThatThrownBy(() -> map(results)).hasMessageContaining("duplicate nickname");
        ((ObjectNode) results.get(1)).put("nickname", "synthetic_bravo").put("gameId", 410002);
        assertThatThrownBy(() -> map(results)).hasMessageContaining("shared game fields");
    }

    @Test
    void refusesEmptyMatch() {
        assertThatThrownBy(() -> mapper.mapMatch(List.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("userGames");
    }

    @Test
    void refusesDeathDetailsWithoutKillerTypeBeforeRawCouldLoseValues() throws Exception {
        List<JsonNode> results = fixture();
        ((ObjectNode) results.getFirst()).put("killer", "");
        assertThatThrownBy(() -> map(results)).hasMessageContaining("killer");
    }

    @Test
    void errorMessageDoesNotExposeRawValueOrNickname() throws Exception {
        List<JsonNode> results = fixture();
        ((ObjectNode) results.getFirst()).put("victory", "private_test_string");
        assertThatThrownBy(() -> map(results)).hasMessageContaining("victory")
                .hasMessageNotContaining("private_test_string")
                .hasMessageNotContaining("synthetic_alpha");
    }

    @ParameterizedTest(name = "{0}: invalid {1}")
    @MethodSource("invalidFields")
    void rejectsMalformedValuesInsteadOfDroppingThem(String key, String value) throws Exception {
        List<JsonNode> results = fixture();
        ((ObjectNode) results.getFirst()).set(key, json.readTree(value));
        assertThatThrownBy(() -> map(results)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(key);
    }

    static Stream<Arguments> invalidFields() {
        return Stream.of(
                Arguments.of("victory", "1.5"),
                Arguments.of("victory", "2147483648"),
                Arguments.of("victory", "\"1\""),
                Arguments.of("gambit", "1"),
                Arguments.of("gambit", "\"true\""),
                Arguments.of("attackSpeed", "\"1.25\""),
                Arguments.of("totalVFCredits", "{}"),
                Arguments.of("totalVFCredits", "[\"1\"]"),
                Arguments.of("equipment", "[]"),
                Arguments.of("equipment", "{\"40000\":110001}"),
                Arguments.of("equipmentGrade", "{}"),
                Arguments.of("equipmentGrade", "{\"0\":4,\"1\":5,\"2\":3}"),
                Arguments.of("equipFirstItemForLog", "{\"0\":[null]}"),
                Arguments.of("equipFirstItemForLog", "{\"0\":[1,1]}"),
                Arguments.of("traitFirstSub", "[1,1]"),
                Arguments.of("traitSecondSub", "[null]"),
                Arguments.of("masteryLevel", "{\"x\":1}"),
                Arguments.of("killDetails", "\"not-json\""),
                Arguments.of("killDetails", "\"{\\\"12\\\":null}\""),
                Arguments.of("killDetails", "\"{\\\"12\\\":0}\""),
                Arguments.of("deathDetails", "\"[]\""),
                Arguments.of("skillOrderInfo", "{\"x\":1001}"),
                Arguments.of("placeOfStart", "\"bad\""),
                Arguments.of("placeOfDeath", "\"bad\""),
                Arguments.of("startDtm", "\"not-a-date\""),
                Arguments.of("gameId", "true"),
                Arguments.of("gameId", "42.5"),
                Arguments.of("gameId", "9223372036854775808"),
                Arguments.of("gameId", "\"410001\""),
                Arguments.of("gameId", "null"),
                Arguments.of("nickname", "\"\""),
                Arguments.of("nickname", "42"),
                Arguments.of("startDtm", "1"),
                Arguments.of("totalVFCredits", "\"\""),
                Arguments.of("equipment", "{\"00\":110003,\"1\":210003}"),
                Arguments.of("equipment", "{\"0\":null,\"1\":210003}"),
                Arguments.of("equipmentGrade", "{\"0\":40000,\"1\":5}"),
                Arguments.of("equipmentGrade", "\"\""),
                Arguments.of("traitFirstSub", "\"\""),
                Arguments.of("masteryLevel", "{\"01\":1}"),
                Arguments.of("masteryLevel", "{\"16\":\"20\"}"),
                Arguments.of("placeOfStart", "70"),
                Arguments.of("placeOfDeath2", "false"));
    }

    private MappedMatch map(List<JsonNode> results) {
        return mapper.mapMatch(results,
                Map.of("SYNTHETICRANGER", 700, "syntheticranger", 137, "Boar", 999));
    }

    private List<JsonNode> fixture() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/fixtures/battle_user_result_v4.json")) {
            assertThat(stream).isNotNull();
            List<JsonNode> rows = new ArrayList<>();
            json.readTree(stream).get("userGames").forEach(rows::add);
            return rows;
        }
    }

    private String v4() throws Exception {
        return Files.readString(Path.of("../../db/migrations/V4__mvp1_move_raw_to_columns.sql"));
    }
}
