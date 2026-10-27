package io.eranalytics.pipeline.persistence.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class TierSampleCacheTest {
    private final MutableClock clock = new MutableClock();
    private final TierSampleCache cache = new TierSampleCache(Duration.ofHours(1), clock);
    private final AtomicInteger loads = new AtomicInteger();

    @Test
    void computesOnFirstCallThenServesCachedCountsEveryCycleWithinTheHour() {
        Supplier<Map<String, Long>> loader = counting(Map.of("플래티넘", 3L));

        for (int cycle = 0; cycle < 50; cycle++) {
            assertThat(cache.get(41, 4, loader)).containsExactly(Map.entry("플래티넘", 3L));
            clock.advance(Duration.ofSeconds(70));
        }

        assertThat(loads).hasValue(1);
    }

    @Test
    void refreshesOnceAfterTheHourAndUsesTheNewCounts() {
        cache.get(41, 4, counting(Map.of("플래티넘", 3L)));

        clock.advance(Duration.ofMinutes(59));
        assertThat(cache.get(41, 4, counting(Map.of("플래티넘", 9L)))).containsEntry("플래티넘", 3L);
        clock.advance(Duration.ofMinutes(1));
        assertThat(cache.get(41, 4, counting(Map.of("플래티넘", 9L)))).containsEntry("플래티넘", 9L);
        assertThat(cache.get(41, 4, counting(Map.of("플래티넘", 11L)))).containsEntry("플래티넘", 9L);

        assertThat(loads).hasValue(2);
    }

    @Test
    void failedRefreshKeepsPreviousCountsAndWaitsForTheNextInterval() {
        cache.get(41, 4, counting(Map.of("다이아몬드", 5L)));
        clock.advance(Duration.ofHours(1));

        assertThat(cache.get(41, 4, failing())).containsEntry("다이아몬드", 5L);
        clock.advance(Duration.ofMinutes(30));
        assertThat(cache.get(41, 4, failing())).containsEntry("다이아몬드", 5L);
        clock.advance(Duration.ofMinutes(30));
        assertThat(cache.get(41, 4, counting(Map.of("다이아몬드", 8L)))).containsEntry("다이아몬드", 8L);

        // One initial load, one failed refresh, one successful refresh; no retry inside the hour.
        assertThat(loads).hasValue(3);
    }

    @Test
    void failureOnTheFirstCallHasNoPreviousValueSoItIsRethrownAndRetriedNextCall() {
        assertThatThrownBy(() -> cache.get(41, 4, failing()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(cache.get(41, 4, counting(Map.of("플래티넘", 1L)))).containsEntry("플래티넘", 1L);
    }

    @Test
    void changedScopeOrInvalidateRecomputesImmediately() {
        cache.get(41, 4, counting(Map.of("플래티넘", 1L)));

        assertThat(cache.get(41, 5, counting(Map.of("플래티넘", 2L)))).containsEntry("플래티넘", 2L);
        assertThat(cache.get(42, 5, counting(Map.of("플래티넘", 3L)))).containsEntry("플래티넘", 3L);
        cache.invalidate();
        assertThat(cache.get(42, 5, counting(Map.of("플래티넘", 4L)))).containsEntry("플래티넘", 4L);

        assertThat(loads).hasValue(4);
    }

    private Supplier<Map<String, Long>> counting(Map<String, Long> value) {
        return () -> {
            loads.incrementAndGet();
            return value;
        };
    }

    private Supplier<Map<String, Long>> failing() {
        return () -> {
            loads.incrementAndGet();
            throw new IllegalStateException("synthetic database failure");
        };
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-27T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
