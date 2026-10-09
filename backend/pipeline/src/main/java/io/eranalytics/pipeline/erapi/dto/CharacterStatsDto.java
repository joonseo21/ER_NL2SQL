package io.eranalytics.pipeline.erapi.dto;

public record CharacterStatsDto(
        Integer characterLevel,
        Integer skinCode,
        Integer accountLevel,
        Integer maxHp,
        Integer attackPower,
        Integer defense,
        Integer skillAmp,
        Integer adaptiveForce,
        Double ccTimeToPlayer,
        Double attackSpeed,
        Double moveSpeed,
        Double coolDownReduction,
        Double criticalStrikeChance,
        Double lifeSteal,
        Double attackRange,
        Double sightRange
) {
}
