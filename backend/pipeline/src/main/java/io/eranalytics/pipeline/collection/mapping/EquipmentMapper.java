package io.eranalytics.pipeline.collection.mapping;

import static io.eranalytics.pipeline.erapi.jackson.BattleJsonValues.invalid;

import io.eranalytics.pipeline.collection.model.EquipmentRow;
import io.eranalytics.pipeline.erapi.dto.EquipmentDto;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public final class EquipmentMapper {
    private EquipmentMapper() {
    }

    public static List<EquipmentRow> map(EquipmentDto input) {
        List<EquipmentRow> rows = new ArrayList<>();
        var items = input.equipment();
        var grades = input.equipmentGrade();
        if (items != null) {
            items.forEach((slot, item) -> {
                if (grades == null || !grades.containsKey(slot)) {
                    throw invalid("equipmentGrade");
                }
                if (item == null) {
                    throw invalid("equipment");
                }
                rows.add(new EquipmentRow("FINAL", slot, item, grades.get(slot)));
            });
        }
        if (grades != null) {
            for (Short slot : grades.keySet()) {
                if (items == null || !items.containsKey(slot)) {
                    throw invalid("equipmentGrade");
                }
            }
        }
        if (input.equipFirstItemForLog() != null) {
            input.equipFirstItemForLog().forEach((slot, firstItems) -> {
                if (firstItems == null) {
                    throw invalid("equipFirstItemForLog");
                }
                var seen = new HashSet<Integer>();
                for (Integer item : firstItems) {
                    if (item == null || !seen.add(item)) {
                        throw invalid("equipFirstItemForLog");
                    }
                    rows.add(new EquipmentRow("FIRST", slot, item, null));
                }
            });
        }
        return List.copyOf(rows);
    }
}
