package io.eranalytics.pipeline.erapi.dto;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** Role-specific views of one flat API result; raw retains unknown fields. */
public record BattleUserResultDto(
        GameResultDto game,
        ParticipantCoreDto core,
        CombatDto combat,
        CharacterStatsDto stats,
        CreditDto credits,
        CraftDto crafting,
        ActivityDto activity,
        LoadoutDto loadout,
        EquipmentDto equipment,
        TraitsDto traits,
        MasteryDto mastery,
        MatchupDto matchup,
        List<DeathDto> deaths,
        ObjectNode raw
) {}
