package io.eranalytics.pipeline.collection.route;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;

/** Whitelisted response fields only: author identity, title, description and raw are discarded. */
public record RouteSnapshot(
        int id,
        Integer v2SeasonId,
        int characterCode,
        int weaponType,
        Integer tacticalSkillGroupCode,
        List<Integer> weaponCodes,
        List<Integer> traitCodes,
        List<Integer> remoteTransferItemCodes,
        JsonNode lateGameItemCodes,
        List<Integer> paths,
        Double v2WinRate,
        Long v2Like,
        Double v2AccumulateWinRate,
        Long v2AccumulateLike,
        Integer routeVersion,
        Instant updateDtm,
        List<Integer> version,
        Integer teamMode,
        Boolean share,
        int responseCode) {}
