package io.eranalytics.pipeline.erapi.dto;

import java.util.List;

public record CraftDto(
        Integer craftUncommon,
        Integer craftRare,
        Integer craftEpic,
        Integer craftLegend,
        Integer craftMythic,
        Integer campFireCraftUncommon,
        Integer campFireCraftRare,
        Integer campFireCraftEpic,
        Integer campFireCraftLegendary,
        List<Integer> foodCraftCount
) {
}
