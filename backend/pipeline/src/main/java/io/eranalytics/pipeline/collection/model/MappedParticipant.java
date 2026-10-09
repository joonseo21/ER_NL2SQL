package io.eranalytics.pipeline.collection.model;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

public record MappedParticipant(ParticipantData data, List<EquipmentRow> equipment,
                         List<TraitRow> traits, List<MasteryRow> mastery, List<MatchupRow> matchups,
                         List<DeathRow> deaths, ObjectNode remainingRaw) {
}
