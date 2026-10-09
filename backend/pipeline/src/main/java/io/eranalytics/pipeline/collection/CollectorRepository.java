package io.eranalytics.pipeline.collection;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Optional;

/** Storage operations used by the collection flow and API client. */
public interface CollectorRepository {
    void upsertRankerAndQueue(String userId, String nickname, Integer rank, Integer mmr, int seasonId);

    void enqueue(String jobType, String targetKey);

    Optional<QueueJob> claimNext();

    void markDone(long id);

    void markFailure(QueueJob job, String error, boolean retryable);

    void upsertGame(List<JsonNode> results);

    void logApiCall(String endpoint, int statusCode, long latencyMs);

    void upsertCharacter(int code, String nameKo, String nameEn);
}
