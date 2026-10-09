package io.eranalytics.pipeline.erapi.dto;

import java.util.Map;

public record MasteryDto(
        Map<Integer, Integer> masteryLevel
) {
}
