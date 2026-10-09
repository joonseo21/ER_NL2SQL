package io.eranalytics.pipeline.collection.model;

import com.fasterxml.jackson.databind.JsonNode;

public record ParticipantRow(long gameId, String nickname, Integer teamNumber, Integer characterNum,
                      Integer bestWeapon, Integer bestWeaponLevel, Integer gameRank, Integer playerKill,
                      Integer playerAssistant, Integer monsterKill, Integer damageToPlayer,
                      Integer mmrBefore, Integer mmrGain, Integer mmrAfter, Integer playTime, JsonNode raw) {
}
