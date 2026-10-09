package io.eranalytics.pipeline.collection.mapping;

import static io.eranalytics.pipeline.erapi.jackson.BattleJsonValues.invalid;

import io.eranalytics.pipeline.collection.model.MasteryRow;
import io.eranalytics.pipeline.erapi.dto.MasteryDto;
import java.util.ArrayList;
import java.util.List;

public final class MasteryMapper {
    private MasteryMapper() {
    }

    public static List<MasteryRow> map(MasteryDto input) {
        List<MasteryRow> rows = new ArrayList<>();
        if (input.masteryLevel() != null) {
            input.masteryLevel().forEach((code, level) -> {
                if (level == null) {
                    throw invalid("masteryLevel");
                }
                rows.add(new MasteryRow(code, level));
            });
        }
        return List.copyOf(rows);
    }
}
