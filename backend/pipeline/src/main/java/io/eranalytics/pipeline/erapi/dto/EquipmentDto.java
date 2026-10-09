package io.eranalytics.pipeline.erapi.dto;

import java.util.List;
import java.util.Map;

public record EquipmentDto(
        Map<Short, Integer> equipment,
        Map<Short, Short> equipmentGrade,
        Map<Short, List<Integer>> equipFirstItemForLog
) {
}
