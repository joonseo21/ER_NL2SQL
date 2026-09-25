package io.eranalytics.pipeline;

import org.springframework.stereotype.Component;

@Component
final class RequestRateGate {
    private final long intervalNanos;
    private long nextAllowedAt;

    RequestRateGate(ErApiProperties properties) {
        if (properties.requestsPerSecond() <= 0) {
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
                throw new IllegalStateException("Interrupted while waiting for ER API rate limit", exception);
            }
        }
        nextAllowedAt = System.nanoTime() + intervalNanos;
    }
}
