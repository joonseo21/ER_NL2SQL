package io.eranalytics.pipeline.collection.model;

import java.util.Map;
import java.util.List;

public record MappedMatch(Map<String, Object> gameColumns, List<MappedParticipant> participants) {
}
