package io.eranalytics.pipeline.collection;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.eranalytics.pipeline.collection.model.GameData;
import io.eranalytics.pipeline.collection.model.ParticipantData;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Test-only column views keep the independent V4 contract assertions readable. */
public final class StoredColumnAssertions {
    private StoredColumnAssertions() {
    }

    public static Map<String, Object> game(GameData game) {
        var values = fields(List.of(game.fields()));
        values.put("team_count", game.teamCount());
        return values;
    }

    public static Map<String, Object> participant(ParticipantData data) {
        var values = fields(List.of(data.core(), data.combat(), data.stats(), data.credits(),
                data.crafting(), data.activity(), data.loadout()));
        values.put("version_major", data.versionMajor());
        values.put("version_minor", data.versionMinor());
        return values;
    }

    private static Map<String, Object> fields(List<Object> records) {
        Map<String, Object> values = new LinkedHashMap<>();
        var names = new PropertyNamingStrategies.SnakeCaseStrategy();
        try {
            for (Object record : records) {
                for (RecordComponent component : record.getClass().getRecordComponents()) {
                    values.put(names.translate(component.getName()), component.getAccessor().invoke(record));
                }
            }
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
        return values;
    }
}
