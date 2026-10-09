package io.eranalytics.pipeline.collection.model;

import java.util.List;

public record MappedMatch(GameData game, List<MappedParticipant> participants) {
}
