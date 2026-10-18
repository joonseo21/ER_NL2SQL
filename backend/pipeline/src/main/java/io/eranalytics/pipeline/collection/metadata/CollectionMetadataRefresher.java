package io.eranalytics.pipeline.collection.metadata;

import io.eranalytics.pipeline.config.CollectionProperties;
import io.eranalytics.pipeline.config.ErApiProperties;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** Checks only uninitialized/current-season-mismatched names, at startup and metadata intervals. */
@Component
public class CollectionMetadataRefresher {
    private final NameMatchUsageStore store;
    private final NameMetadataRefresher names;
    private final CollectionProperties settings;
    private final ErApiProperties apiSettings;
    private final Clock clock;
    private Instant lastChecked;

    public CollectionMetadataRefresher(
            NameMatchUsageStore store,
            NameMetadataRefresher names,
            CollectionProperties settings,
            ErApiProperties apiSettings,
            Clock clock) {
        this.store = store;
        this.names = names;
        this.settings = settings;
        this.apiSettings = apiSettings;
        this.clock = clock;
    }

    public void refreshIfDue() {
        names.refreshIfDue();
        Instant now = clock.instant();
        if (lastChecked != null && now.isBefore(lastChecked.plus(settings.metaRefresh()))) return;
        store.initializeMissing(apiSettings.seasonId(), now);
        lastChecked = now;
    }
}
