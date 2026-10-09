package io.eranalytics.pipeline.erapi.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CombatDto(
        @JsonProperty("damageToPlayer_basic") Integer damageToPlayerBasic,
        @JsonProperty("damageToPlayer_skill") Integer damageToPlayerSkill,
        @JsonProperty("damageToPlayer_itemSkill") Integer damageToPlayerItemSkill,
        @JsonProperty("damageToPlayer_direct") Integer damageToPlayerDirect,
        Integer damageToMonster,
        Integer damageFromPlayer,
        @JsonProperty("damageFromPlayer_basic") Integer damageFromPlayerBasic,
        @JsonProperty("damageFromPlayer_skill") Integer damageFromPlayerSkill,
        @JsonProperty("damageFromPlayer_itemSkill") Integer damageFromPlayerItemSkill,
        @JsonProperty("damageFromPlayer_direct") Integer damageFromPlayerDirect,
        Integer damageFromMonster,
        @JsonProperty("damageOffsetedByShield_Player") Integer damageOffsetedByShieldPlayer,
        Integer healAmount,
        Integer teamRecover,
        Integer protectAbsorb,
        Integer teamDown,
        Integer teamElimination,
        Integer terminateCount,
        Integer clutchCount,
        Integer killsPhaseOne,
        Integer killsPhaseTwo,
        Integer killsPhaseThree,
        Integer deathsPhaseOne,
        Integer deathsPhaseTwo,
        Integer deathsPhaseThree
) {
}
