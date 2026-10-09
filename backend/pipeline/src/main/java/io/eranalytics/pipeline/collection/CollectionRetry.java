package io.eranalytics.pipeline.collection;

import io.eranalytics.pipeline.erapi.ApiException;
import io.eranalytics.pipeline.collection.model.RetryResult;
import java.io.IOException;
import java.time.Duration;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;

/** The sole retry boundary: 재시도 가능 여부, 총 5회 제한, 지수 백오프를 공통으로 관리. */
@Component
public class CollectionRetry {
    public static final int MAX_ATTEMPTS = 5;
    private static final Logger log = LoggerFactory.getLogger(CollectionRetry.class);
    private final CollectionSleeper sleeper;

    public CollectionRetry(CollectionSleeper sleeper) {
        this.sleeper = sleeper;
    }

    public <T> T call(String operation, Supplier<T> action) {
        var result = run(operation, 1, attempt -> {}, (attempt, error) -> {}, action);
        if (result.failure() != null) throw result.failure();
        return result.value();
    }

    public <T> RetryResult<T> run(String operation, int firstAttempt, IntConsumer beforeAttempt,
                      BiConsumer<Integer, RuntimeException> onFailure, Supplier<T> action) {
        if (firstAttempt < 1 || firstAttempt > MAX_ATTEMPTS) {
            throw new IllegalArgumentException("Invalid retry attempt");
        }
        for (int attempt = firstAttempt; attempt <= MAX_ATTEMPTS; attempt++) {
            beforeAttempt.accept(attempt);
            try {
                return new RetryResult<>(action.get(), null);
            } catch (RuntimeException error) {
                if (Thread.currentThread().isInterrupted() || error instanceof CollectionInterruptedException) {
                    throw new CollectionInterruptedException();
                }
                onFailure.accept(attempt, error);
                if (!retryable(error) || attempt == MAX_ATTEMPTS) {
                    log.warn("Collection operation {} failed finally at attempt {}/{} ({})",
                            operation, attempt, MAX_ATTEMPTS, reason(error));
                    return new RetryResult<>(null, error);
                }
                Duration delay = Duration.ofSeconds(Math.min(60, 1L << (attempt - 1)));
                log.warn("Collection operation {} attempt {}/{} failed ({}); retry in {}",
                        operation, attempt, MAX_ATTEMPTS, reason(error), delay);
                sleeper.sleep(delay);
            }
        }
        throw new IllegalStateException("Unreachable retry state");
    }

    public static boolean retryable(RuntimeException error) {
        if (error instanceof ApiException api) return api.retryable();
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ResourceAccessException || cause instanceof IOException
                    || cause instanceof TransientDataAccessException) return true;
        }
        return false;
    }

    /** Do not persist exception messages containing request URLs/nicknames or DB row values. */
    public static String reason(RuntimeException error) {
        if (error instanceof ApiException api) return "ER API code " + api.getStatusCode();
        return error.getClass().getSimpleName();
    }
}
