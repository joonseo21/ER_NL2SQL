package io.eranalytics.pipeline.collection.route;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class RouteMapper {
    private final ObjectMapper json;

    public RouteMapper(ObjectMapper json) {
        this.json = json;
    }

    public RouteSnapshot map(RouteKey key, JsonNode response) {
        JsonNode row = response.path("result").path("recommendWeaponRoute");
        if (requiredInt(row, "id") != key.routeId()) throw invalid();
        return new RouteSnapshot(
                key.routeId(),
                integer(row, "v2SeasonId"),
                requiredInt(row, "characterCode"),
                requiredInt(row, "weaponType"),
                integer(row, "tacticalSkillGroupCode"),
                array(row, "weaponCodes"),
                array(row, "traitCodes"),
                array(row, "remoteTransferItemCodes"),
                lateItems(row),
                array(row, "paths"),
                decimal(row, "v2WinRate"),
                longValue(row, "v2Like"),
                decimal(row, "v2AccumulateWinRate"),
                longValue(row, "v2AccumulateLike"),
                integer(row, "routeVersion"),
                instant(row),
                version(row.path("version")),
                integer(row, "teamMode"),
                share(row),
                response.path("code").asInt(200));
    }

    private JsonNode encoded(JsonNode row, String field) {
        JsonNode value = row.path(field);
        if (!value.isTextual()) throw invalid();
        try {
            return json.readTree(value.textValue());
        } catch (JsonProcessingException error) {
            throw invalid();
        }
    }

    private List<Integer> array(JsonNode row, String field) {
        JsonNode values = encoded(row, field);
        if (values == null || !values.isArray()) throw invalid();
        List<Integer> result = new ArrayList<>();
        for (JsonNode value : values) {
            if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() <= 0) {
                throw invalid();
            }
            result.add(value.intValue());
        }
        return List.copyOf(result);
    }

    private JsonNode lateItems(JsonNode row) {
        JsonNode items = encoded(row, "lateGameItemCodes");
        if (items == null || !items.isObject()) throw invalid();
        items.fields()
                .forEachRemaining(
                        entry -> {
                            if (!entry.getKey().matches("[0-9]+") || !entry.getValue().isArray()) {
                                throw invalid();
                            }
                            for (JsonNode item : entry.getValue()) {
                                if (!item.isIntegralNumber() || !item.canConvertToInt()) {
                                    throw invalid();
                                }
                            }
                        });
        return items;
    }

    private static List<Integer> version(JsonNode value) {
        if (!value.isTextual() || !value.textValue().matches("[0-9]+\\.[0-9]+\\.[0-9]+")) {
            return List.of();
        }
        try {
            return java.util.Arrays.stream(value.textValue().split("\\."))
                    .map(Integer::valueOf)
                    .toList();
        } catch (NumberFormatException error) {
            return List.of();
        }
    }

    private static Boolean share(JsonNode row) {
        JsonNode value = row.path("share");
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isBoolean()) throw invalid();
        return value.booleanValue();
    }

    private static Instant instant(JsonNode row) {
        Long value = longValue(row, "updateDtm");
        return value == null ? null : Instant.ofEpochMilli(value);
    }

    private static int requiredInt(JsonNode row, String field) {
        Integer value = integer(row, field);
        if (value == null || value <= 0) throw invalid();
        return value;
    }

    private static Integer integer(JsonNode row, String field) {
        Long value = longValue(row, field);
        if (value == null) return null;
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw invalid();
        return value.intValue();
    }

    private static Long longValue(JsonNode row, String field) {
        JsonNode value = row.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToLong()) throw invalid();
        return value.longValue();
    }

    private static Double decimal(JsonNode row, String field) {
        JsonNode value = row.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isNumber() || !Double.isFinite(value.doubleValue())) throw invalid();
        return value.doubleValue();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid route response");
    }
}
