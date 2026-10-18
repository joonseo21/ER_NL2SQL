package io.eranalytics.pipeline.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("er.collection")
public record CollectionProperties(
        boolean enabled,
        @DefaultValue("FULL") CollectionMode mode,
        boolean continuous,
        Duration revisitInterval,
        int maxPagesPerUser,
        Duration idleSleep,
        Duration metaRefresh,
        int minVersionMajor,
        int minSeedMmr,
        BalanceBasis balanceBasis) {
    public void validate() {
        if (mode == null) throw new IllegalStateException("Collection mode must be configured");
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
