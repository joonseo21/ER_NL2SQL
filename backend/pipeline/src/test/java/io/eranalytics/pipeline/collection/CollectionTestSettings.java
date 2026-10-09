package io.eranalytics.pipeline.collection;

import io.eranalytics.pipeline.config.CollectionProperties;
import io.eranalytics.pipeline.config.BalanceBasis;
import java.time.Duration;

final class CollectionTestSettings {
    static CollectionProperties settings(int pages, boolean continuous) {
        return new CollectionProperties(true, continuous, Duration.ofHours(4), pages,
                Duration.ofMinutes(5), Duration.ofHours(24), 4, 3600, BalanceBasis.LATEST_PATCH);
    }
}
