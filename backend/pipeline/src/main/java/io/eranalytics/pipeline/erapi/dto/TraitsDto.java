package io.eranalytics.pipeline.erapi.dto;

import java.util.List;

public record TraitsDto(
        List<Integer> traitFirstSub,
        List<Integer> traitSecondSub
) {
}
