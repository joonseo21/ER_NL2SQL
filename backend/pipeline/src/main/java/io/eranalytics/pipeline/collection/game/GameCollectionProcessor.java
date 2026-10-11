package io.eranalytics.pipeline.collection.game;

import io.eranalytics.pipeline.collection.retry.CollectionRetryExecutor;
import io.eranalytics.pipeline.erapi.ErApiClient;
import org.springframework.stereotype.Component;

/** GAME 작업 하나를 끝까지 처리. API 조회·저장을 실행하고 재시도마다 DB에 시도·실패를 기록. */
@Component
public class GameCollectionProcessor {
    private final ErApiClient api;
    private final GameQueueRepository queue;
    private final CollectedGameStore games;
    private final CollectionRetryExecutor retry;

    public GameCollectionProcessor(
            ErApiClient api,
            GameQueueRepository queue,
            CollectedGameStore games,
            CollectionRetryExecutor retry) {
        this.api = api;
        this.queue = queue;
        this.games = games;
        this.retry = retry;
    }

    public boolean processNext() {
        return queue.claimNextGame()
                .map(
                        job -> {
                            process(job);
                            return true;
                        })
                .orElse(false);
    }

    public void process(GameCollectionJob job) {
        // No retry wrapper inside gameParticipants: one job has one attempt budget.
        retry.run(
                "game job " + job.id(),
                job.attempts(),
                attempt -> queue.recordAttempt(job, attempt),
                (attempt, error) ->
                        queue.markFailure(
                                new GameCollectionJob(
                                        job.id(), job.jobType(), job.targetKey(), attempt),
                                CollectionRetryExecutor.reason(error),
                                CollectionRetryExecutor.retryable(error)),
                () -> {
                    games.completeGame(job, api.gameParticipants(job.targetKey()));
                    return null;
                });
        // Attempt/failure bookkeeping errors propagate; only recorded terminal action failure ends
        // this job.
    }
}
