package io.eranalytics.pipeline.collection.route;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.eranalytics.pipeline.erapi.ErApiClient;
import io.eranalytics.pipeline.erapi.ErApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RouteCollectionProcessorTest {
    private final RouteStore store = mock(RouteStore.class);
    private final ErApiClient api = mock(ErApiClient.class);
    private final Instant now = Instant.parse("2026-10-17T00:00:00Z");
    private final RouteKey first = new RouteKey(41, 7039);

    private RouteCollectionProcessor processor(int count) {
        return new RouteCollectionProcessor(
                store,
                api,
                new RouteMapper(new ObjectMapper()),
                Clock.fixed(now, ZoneOffset.UTC),
                count);
    }

    @Test
    void records404OnceAndContinuesBatchToSuccessfulRoute() {
        RouteKey second = new RouteKey(41, 7040);
        when(store.claimNext(now)).thenReturn(Optional.of(first), Optional.of(second));
        when(api.recommendRoute(7039)).thenThrow(new ErApiException(404, "unavailable"));
        when(api.recommendRoute(7040)).thenReturn(RouteFixture.response(7040));
        processor(2).processBatch();
        verify(api, times(1)).recommendRoute(7039);
        verify(store).fail(first, "NOT_FOUND", 404, now);
        verify(store).save(eq(second), any(), eq(now));
    }

    @Test
    void defaultBudgetDoesNotDrainEntirePool() {
        when(store.claimNext(now)).thenReturn(Optional.of(first));
        when(api.recommendRoute(7039)).thenReturn(RouteFixture.response(7039));
        processor(1).processBatch();
        verify(store, times(1)).claimNext(now);
    }

    @Test
    void databaseFailureIsNotSwallowedOrRecordedAsApiFailure() {
        when(store.claimNext(now)).thenReturn(Optional.of(first));
        when(api.recommendRoute(7039)).thenReturn(RouteFixture.response(7039));
        doThrow(new IllegalStateException("DB failure"))
                .when(store)
                .save(eq(first), any(), eq(now));
        assertThatThrownBy(() -> processor(1).processBatch())
                .isInstanceOf(IllegalStateException.class);
        verify(store, never()).fail(any(), any(), any(), any());
    }
}
