package io.eranalytics.pipeline.collection.model;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record MappedParticipant(Map<String, Object> columns, List<EquipmentRow> equipment,
                         List<TraitRow> traits, List<MasteryRow> mastery, List<MatchupRow> matchups,
                         List<DeathRow> deaths, ObjectNode remainingRaw) {
}
