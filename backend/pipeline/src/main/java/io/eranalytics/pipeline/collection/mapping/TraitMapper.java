package io.eranalytics.pipeline.collection.mapping;

import static io.eranalytics.pipeline.erapi.jackson.BattleJsonValues.invalid;

import io.eranalytics.pipeline.collection.model.TraitRow;
import io.eranalytics.pipeline.erapi.dto.TraitsDto;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public final class TraitMapper {
    private TraitMapper() {
    }

    public static List<TraitRow> map(Integer core, TraitsDto input) {
        List<TraitRow> rows = new ArrayList<>();
        if (core != null) {
            rows.add(new TraitRow("CORE", core));
        }
        add(rows, input.traitFirstSub(), "FIRST_SUB", "traitFirstSub");
        add(rows, input.traitSecondSub(), "SECOND_SUB", "traitSecondSub");
        return List.copyOf(rows);
    }

    private static void add(List<TraitRow> rows, List<Integer> codes, String slot, String field) {
        if (codes == null) {
            return;
        }
        var seen = new HashSet<Integer>();
        for (Integer code : codes) {
            if (code == null || !seen.add(code)) {
                throw invalid(field);
            }
            rows.add(new TraitRow(slot, code));
        }
    }
}
