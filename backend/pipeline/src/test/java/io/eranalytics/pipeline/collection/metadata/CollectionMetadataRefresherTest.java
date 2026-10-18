package io.eranalytics.pipeline.collection.metadata;

import static io.eranalytics.pipeline.collection.CollectionTestSettings.settings;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import io.eranalytics.pipeline.config.ErApiProperties;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class CollectionMetadataRefresherTest {
    private final NameMatchUsageStore store = mock(NameMatchUsageStore.class);
    private final NameMetadataRefresher names = mock(NameMetadataRefresher.class);
    private final Clock clock = mock(Clock.class);
    private final Instant start = Instant.parse("2026-10-17T00:00:00Z");

    private CollectionMetadataRefresher refresher() {
        return new CollectionMetadataRefresher(
                store,
                names,
                settings(30, false),
                new ErApiProperties("https://example.invalid", "synthetic-key", 41, 0.5),
                clock);
    }

    @Test
    void checksMissingNamesAtStartupAndAfterMetadataIntervalOnly() {
        when(clock.instant()).thenReturn(start, start.plusSeconds(60), start.plusSeconds(86400));
        CollectionMetadataRefresher refresher = refresher();
        refresher.refreshIfDue();
        refresher.refreshIfDue();
        refresher.refreshIfDue();
        InOrder order = inOrder(names, store);
        order.verify(names).refreshIfDue();
        order.verify(store).initializeMissing(41, start);
        order.verify(names, times(2)).refreshIfDue();
        order.verify(store).initializeMissing(41, start.plusSeconds(86400));
        verifyNoMoreInteractions(store);
    }

    @Test
    void failedInitializationDoesNotMarkTheSeasonChecked() {
        when(clock.instant()).thenReturn(start);
        doThrow(new IllegalStateException("synthetic failure"))
                .doNothing()
                .when(store)
                .initializeMissing(41, start);
        CollectionMetadataRefresher refresher = refresher();
        assertThatThrownBy(refresher::refreshIfDue).isInstanceOf(IllegalStateException.class);
        refresher.refreshIfDue();
        verify(store, times(2)).initializeMissing(41, start);
    }
}
