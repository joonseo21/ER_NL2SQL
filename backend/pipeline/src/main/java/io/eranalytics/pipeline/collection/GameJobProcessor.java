package io.eranalytics.pipeline.collection;

import io.eranalytics.pipeline.erapi.ErApiClient;
import org.springframework.stereotype.Component;

/**
 * GAME 작업 하나를 끝까지 처리. API 조회·저장을 실행하고 재시도마다 DB에 시도·실패를 기록.
 */
@Component
public class GameJobProcessor {
    private final ErApiClient api;
    private final CollectorRepository repository;
    private final CollectionRetry retry;

    public GameJobProcessor(ErApiClient api, CollectorRepository repository, CollectionRetry retry) {
        this.api = api;
        this.repository = repository;
        this.retry = retry;
    }

    public void process(QueueJob job) {
        // No retry wrapper inside gameParticipants: one job has one attempt budget.
        retry.run("game job " + job.id(), job.attempts(),
                attempt -> repository.recordAttempt(job, attempt),
                (attempt, error) -> repository.markFailure(
                        new QueueJob(job.id(), job.jobType(), job.targetKey(), attempt),
                        CollectionRetry.reason(error), CollectionRetry.retryable(error)),
                () -> {
                    repository.completeGame(job, api.gameParticipants(job.targetKey()));
                    return null;
                });
        // Attempt/failure bookkeeping errors propagate; only recorded terminal action failure ends this job.
    }
}
