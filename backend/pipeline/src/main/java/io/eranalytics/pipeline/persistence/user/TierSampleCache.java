package io.eranalytics.pipeline.persistence.user;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps the latest-patch participant count per tier so user selection does not run the aggregate
 * every cycle. The first call computes it, later calls refresh it at most once per interval, and a
 * failed refresh keeps the previous value until the next interval.
 */
final class TierSampleCache {
    private static final Logger log = LoggerFactory.getLogger(TierSampleCache.class);

    private final Duration interval;
    private final Clock clock;
    private Key key;
    private Map<String, Long> samples;
    private Instant attemptedAt;

    TierSampleCache(Duration interval, Clock clock) {
        this.interval = interval;
        this.clock = clock;
    }

    /**
     * Returns the cached counts for the scope, calling {@code loader} only on the first call, after
     * the interval, or when the scope changed. A failure with no previous value is rethrown.
     */
    synchronized Map<String, Long> get(
            int seasonId, int minVersionMajor, Supplier<Map<String, Long>> loader) {
        Key requested = new Key(seasonId, minVersionMajor);
        Instant now = clock.instant();
        boolean sameScope = requested.equals(key);
        if (sameScope && now.isBefore(attemptedAt.plus(interval))) return samples;
        try {
            Map<String, Long> loaded = Map.copyOf(loader.get());
            key = requested;
            samples = loaded;
            attemptedAt = now;
        } catch (RuntimeException e) {
            if (!sameScope) throw e;
            // Retrying every cycle would repeat the slow aggregate, so wait for the next interval.
            attemptedAt = now;
            log.warn("Tier sample refresh failed; keeping the previous counts", e);
        }
        return samples;
    }

    /** Forces the next call to recompute. */
    synchronized void invalidate() {
        key = null;
        samples = null;
        attemptedAt = null;
    }

    private record Key(int seasonId, int minVersionMajor) {}
}
