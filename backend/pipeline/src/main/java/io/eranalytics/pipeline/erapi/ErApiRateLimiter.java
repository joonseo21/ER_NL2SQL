package io.eranalytics.pipeline.erapi;

import io.eranalytics.pipeline.config.ErApiProperties;
import org.springframework.stereotype.Component;

@Component
public final class ErApiRateLimiter {
    private final long intervalNanos;
    private long nextAllowedAt;

    public ErApiRateLimiter(ErApiProperties properties) {
        if (!Double.isFinite(properties.requestsPerSecond())
                || properties.requestsPerSecond() <= 0) {
            throw new IllegalArgumentException("er.api.requests-per-second must be positive");
        }
        this.intervalNanos = (long) (1_000_000_000D / properties.requestsPerSecond());
    }

    synchronized void awaitTurn() {
        long now = System.nanoTime();
        long waitNanos = nextAllowedAt - now;
        if (waitNanos > 0) {
            try {
                Thread.sleep(waitNanos / 1_000_000, (int) (waitNanos % 1_000_000));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while waiting for ER API rate limit", exception);
            }
        }
        nextAllowedAt = System.nanoTime() + intervalNanos;
    }
}
