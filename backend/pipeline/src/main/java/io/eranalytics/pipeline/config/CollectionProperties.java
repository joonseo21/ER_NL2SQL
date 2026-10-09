package io.eranalytics.pipeline.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("er.collection")
public record CollectionProperties(
        boolean enabled,
        boolean continuous,
        Duration revisitInterval,
        int maxPagesPerUser,
        Duration idleSleep,
        Duration metaRefresh,
        int minVersionMajor,
        int minSeedMmr,
        BalanceBasis balanceBasis
) {
    public void validate() {
        if (balanceBasis != BalanceBasis.LATEST_PATCH) {
            throw new IllegalStateException("Collection balance basis must be LATEST_PATCH");
        }
        if (maxPagesPerUser < 1 || minVersionMajor < 0 || minSeedMmr < 0) {
            throw new IllegalStateException("Collection page/version/MMR settings are invalid");
        }
        for (Duration duration : new Duration[] {revisitInterval, idleSleep, metaRefresh}) {
            if (duration == null || duration.isNegative() || duration.isZero()) {
                throw new IllegalStateException("Collection durations must be positive");
            }
        }
    }
}
