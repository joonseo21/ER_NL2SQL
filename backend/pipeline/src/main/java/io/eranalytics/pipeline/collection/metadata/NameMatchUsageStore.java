package io.eranalytics.pipeline.collection.metadata;

import java.time.Instant;

/** Materialized name disambiguation evidence, independent of official metadata fetching. */
public interface NameMatchUsageStore {
    void initializeMissing(int seasonId, Instant checkedAt);

    MatchNameCodes gameCodes(long gameId);

    void recordGame(long gameId, MatchNameCodes previous, Instant checkedAt);
}
