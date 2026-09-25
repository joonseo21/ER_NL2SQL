package io.eranalytics.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

final class BattleUserResultMapper {
    // ER API sends offsets without a colon (e.g. +0900), which OffsetDateTime.parse rejects.
    private static final Pattern COMPACT_OFFSET = Pattern.compile("(?<=\\d)([+-]\\d{2})(\\d{2})$");

    private BattleUserResultMapper() {
    }

    static ParticipantRow participant(JsonNode node) {
        return new ParticipantRow(
                requiredLong(node, "gameId"),
                requiredText(node, "nickname"),
                nullableInt(node, "teamNumber"),
                nullableInt(node, "characterNum"),
                nullableInt(node, "bestWeapon"),
                nullableInt(node, "bestWeaponLevel"),
                nullableInt(node, "gameRank"),
                nullableInt(node, "playerKill"),
                nullableInt(node, "playerAssistant"),
                nullableInt(node, "monsterKill"),
                nullableInt(node, "damageToPlayer"),
                nullableInt(node, "mmrBefore"),
                nullableInt(node, "mmrGain"),
                nullableInt(node, "mmrAfter"),
                nullableInt(node, "playTime"),
                node
        );
    }

    static GameRow game(JsonNode node) {
        return new GameRow(
                requiredLong(node, "gameId"),
                nullableInt(node, "seasonId"),
                nullableInt(node, "matchingMode"),
                nullableInt(node, "matchingTeamMode"),
                nullableInt(node, "versionSeason"),
                nullableInt(node, "versionMajor"),
                nullableInt(node, "versionMinor"),
                nullableOffsetDateTime(node, "startDtm"),
                nullableText(node, "serverName")
        );
    }

    private static long requiredLong(JsonNode node, String field) {
        if (!node.hasNonNull(field) || !node.get(field).canConvertToLong()) {
            throw new IllegalArgumentException("Missing or invalid field: " + field);
        }
        return node.get(field).longValue();
    }

    private static String requiredText(JsonNode node, String field) {
        String value = nullableText(node, field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing or invalid field: " + field);
        }
        return value;
    }

    private static Integer nullableInt(JsonNode node, String field) {
        return node.hasNonNull(field) && node.get(field).canConvertToInt()
                ? node.get(field).intValue()
                : null;
    }

    private static String nullableText(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText(null) : null;
    }

    private static OffsetDateTime nullableOffsetDateTime(JsonNode node, String field) {
        String value = nullableText(node, field);
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(COMPACT_OFFSET.matcher(value.trim()).replaceFirst("$1:$2"));
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    record GameRow(
            long gameId, Integer seasonId, Integer matchingMode, Integer matchingTeamMode,
            Integer versionSeason, Integer versionMajor, Integer versionMinor,
            OffsetDateTime startDtm, String serverName
    ) {
    }

    record ParticipantRow(
            long gameId, String nickname, Integer teamNumber, Integer characterNum,
            Integer bestWeapon, Integer bestWeaponLevel, Integer gameRank, Integer playerKill,
            Integer playerAssistant, Integer monsterKill, Integer damageToPlayer,
            Integer mmrBefore, Integer mmrGain, Integer mmrAfter, Integer playTime, JsonNode raw
    ) {
    }
}
