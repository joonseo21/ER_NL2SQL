package io.eranalytics.pipeline.collection.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.eranalytics.pipeline.erapi.dto.ParticipantCoreDto;
import io.eranalytics.pipeline.erapi.dto.CombatDto;
import io.eranalytics.pipeline.erapi.dto.CharacterStatsDto;
import io.eranalytics.pipeline.erapi.dto.CreditDto;
import io.eranalytics.pipeline.erapi.dto.CraftDto;
import io.eranalytics.pipeline.erapi.dto.ActivityDto;
import io.eranalytics.pipeline.erapi.dto.LoadoutDto;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Typed persistence input, with snapshots of mutable array/JSON values. */
public record ParticipantData(
        ParticipantCoreDto core, CombatDto combat, CharacterStatsDto stats,
        CreditDto credits, CraftDto crafting, ActivityDto activity, LoadoutDto loadout,
        Integer versionMajor, Integer versionMinor
) {
    public ParticipantData {
        credits = new CreditDto(
                credits.creditRevivalCount(),
                credits.creditRevivedOthersCount(),
                credits.totalGainVfCredit(),
                credits.totalUseVfCredit(),
                credits.killPlayerGainVfCredit(),
                credits.killChickenGainVfCredit(),
                credits.killBoarGainVfCredit(),
                credits.killWildDogGainVfCredit(),
                credits.killWolfGainVfCredit(),
                credits.killBearGainVfCredit(),
                credits.killBatGainVfCredit(),
                credits.killRavenGainVfCredit(),
                credits.killWicklineGainVfCredit(),
                credits.killAlphaGainVfCredit(),
                credits.killOmegaGainVfCredit(),
                credits.killGammaGainVfCredit(),
                credits.killDroneGainVfCredit(),
                credits.killItemBountyGainVfCredit(),
                copy(credits.totalVfCredits()),
                copy(credits.usedVfCredits()),
                copy(credits.totalTkPerMin()));
        crafting = new CraftDto(
                crafting.craftUncommon(),
                crafting.craftRare(),
                crafting.craftEpic(),
                crafting.craftLegend(),
                crafting.craftMythic(),
                crafting.campFireCraftUncommon(),
                crafting.campFireCraftRare(),
                crafting.campFireCraftEpic(),
                crafting.campFireCraftLegendary(),
                copy(crafting.foodCraftCount()));
        activity = new ActivityDto(
                activity.addTelephotoCamera(),
                activity.removeTelephotoCamera(),
                activity.useHyperLoop(),
                activity.useSecurityConsole(),
                activity.useReconDrone(),
                activity.useEmpDrone(),
                activity.tacticalSkillUseCount(),
                activity.enterDimensionRift(),
                activity.winFromDimensionRift(),
                activity.enterDimensionEmpoweredRift(),
                activity.winFromDimensionEmpoweredRift(),
                activity.sumGetBuffCube(),
                copy(activity.itemTransferredDrone()),
                copy(activity.itemTransferredConsole()));
        loadout = new LoadoutDto(
                loadout.traitFirstCore(),
                loadout.tacticalSkillGroup(),
                loadout.tacticalSkillLevel(),
                loadout.routeIdOfStart(),
                copy(loadout.skillLevelInfo()),
                copy(loadout.creditSource()),
                copy(loadout.killMonsters()),
                loadout.placeOfStart(),
                copy(loadout.skillOrder()));
    }

    private static List<Integer> copy(List<Integer> value) {
        return value == null ? null : Collections.unmodifiableList(new ArrayList<>(value));
    }

    private static JsonNode copy(JsonNode value) {
        return value == null || value.isNull() ? null : value.deepCopy();
    }
}
