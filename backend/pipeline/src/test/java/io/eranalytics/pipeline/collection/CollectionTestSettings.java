package io.eranalytics.pipeline.collection;

import io.eranalytics.pipeline.config.BalanceBasis;
import io.eranalytics.pipeline.config.CollectionMode;
import io.eranalytics.pipeline.config.CollectionProperties;
import java.time.Duration;

public final class CollectionTestSettings {
    public static CollectionProperties settings(int pages, boolean continuous) {
        return settings(pages, continuous, CollectionMode.FULL);
    }

    public static CollectionProperties settings(
            int pages, boolean continuous, CollectionMode mode) {
        return new CollectionProperties(
                true,
                mode,
                continuous,
                Duration.ofHours(4),
                pages,
                Duration.ofMinutes(5),
                Duration.ofHours(24),
                4,
                3600,
                BalanceBasis.LATEST_PATCH);
    }
}
