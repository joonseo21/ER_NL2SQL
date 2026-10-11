package io.eranalytics.pipeline.collection.metadata;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public record NameMetadataBatch(
        Instant checkedAt, Map<NameKind, List<MetadataName>> names, int unresolved) {
    public NameMetadataBatch {
        EnumMap<NameKind, List<MetadataName>> copy =
                new EnumMap<NameKind, List<MetadataName>>(NameKind.class);
        names.forEach((kind, rows) -> copy.put(kind, List.copyOf(rows)));
        names = Map.copyOf(copy);
    }
}
