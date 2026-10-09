package io.eranalytics.pipeline.collection.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Projects trusted column DTOs at the JDBC boundary. API names are declared in DTO
 * annotations; SQL names follow the DTO's Java property names in snake_case.
 * Reflection metadata is cached, and values retain JsonNode/time/numeric types.
 */
public final class SqlColumnProjector {
    private static final PropertyNamingStrategies.SnakeCaseStrategy NAMES =
            new PropertyNamingStrategies.SnakeCaseStrategy();
    private static final ClassValue<RecordComponent[]> COMPONENTS = new ClassValue<>() {
        @Override
        protected RecordComponent[] computeValue(Class<?> type) {
            if (!type.isRecord()) {
                throw new IllegalArgumentException("Column DTO must be a record");
            }
            return type.getRecordComponents();
        }
    };

    private SqlColumnProjector() {
    }

    public static Map<String, Object> project(Object... groups) {
        return project(List.of(groups));
    }

    public static Map<String, Object> project(List<Object> groups) {
        Map<String, Object> columns = new LinkedHashMap<>();
        for (Object group : groups) {
            for (RecordComponent field : COMPONENTS.get(group.getClass())) {
                String column = NAMES.translate(field.getName());
                if (columns.containsKey(column)) {
                    throw new IllegalStateException("Duplicate column DTO field: " + column);
                }
                try {
                    columns.put(column, copyValue(field.getAccessor().invoke(group)));
                } catch (ReflectiveOperationException exception) {
                    throw new IllegalStateException("Could not read column DTO field: " + column, exception);
                }
            }
        }
        return columns;
    }

    private static Object copyValue(Object value) {
        if (value instanceof JsonNode node) {
            return node.isNull() ? null : node.deepCopy();
        }
        if (value instanceof List<?> list) {
            // List.copyOf would reject valid NULL array elements.
            return Collections.unmodifiableList(new ArrayList<>(list));
        }
        return value;
    }
}
