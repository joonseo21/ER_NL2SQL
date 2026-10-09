package io.eranalytics.pipeline.collection;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Optional;

/** Storage operations used by the collection flow and API client. */
public interface CollectorRepository {
    void enqueueGame(long gameId);

    boolean gameExists(long gameId);

    Optional<QueueJob> claimNextGame();

    void recordAttempt(QueueJob job, int attempt);

    void completeGame(QueueJob job, List<JsonNode> results);

    void markFailure(QueueJob job, String error, boolean retryable);

    void upsertGame(List<JsonNode> results);

    void logApiCall(String endpoint, int statusCode, long latencyMs);

    void upsertCharacter(int code, String nameKo, String nameEn);
}
