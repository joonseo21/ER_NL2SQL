package io.eranalytics.pipeline.collection.persistence.entity;

import java.time.OffsetDateTime;

public record LatestUserGame(Integer mmr, Long gameId, OffsetDateTime startedAt) {
}
