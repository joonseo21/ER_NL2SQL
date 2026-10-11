package io.eranalytics.pipeline.collection.game;

import java.util.Optional;

/** Persistent queue state and attempt bookkeeping for the single collection worker. */
public interface GameQueueRepository {
    void enqueueGame(long gameId);

    Optional<GameCollectionJob> claimNextGame();

    void recordAttempt(GameCollectionJob job, int attempt);

    void markFailure(GameCollectionJob job, String error, boolean retryable);
}
