package io.eranalytics.pipeline.collection.runtime;

import static io.eranalytics.pipeline.collection.CollectionTestSettings.settings;
import static org.mockito.Mockito.*;

import io.eranalytics.pipeline.collection.game.GameCollectionProcessor;
import io.eranalytics.pipeline.collection.metadata.CollectionMetadataRefresher;
import io.eranalytics.pipeline.collection.route.RouteCollectionProcessor;
import io.eranalytics.pipeline.collection.user.UserCollectionCoordinator;
import io.eranalytics.pipeline.config.CollectionMode;
import io.eranalytics.pipeline.config.ErApiProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class CollectionWorkerTest {
    private final GameCollectionProcessor games = mock(GameCollectionProcessor.class);
    private final UserCollectionCoordinator users = mock(UserCollectionCoordinator.class);
    private final CollectionMetadataRefresher metadata = mock(CollectionMetadataRefresher.class);
    private final CollectionSleeper sleeper = mock(CollectionSleeper.class);
    private final RouteCollectionProcessor routes = mock(RouteCollectionProcessor.class);

    private CollectionWorker worker(boolean continuous) {
        return worker(continuous, CollectionMode.FULL);
    }

    private CollectionWorker worker(boolean continuous, CollectionMode mode) {
        return worker(continuous, mode, 0);
    }

    private CollectionWorker worker(boolean continuous, CollectionMode mode, int top) {
        return new CollectionWorker(
                games,
                users,
                metadata,
                sleeper,
                new ErApiProperties("https://example.invalid", "synthetic-key", 41, 0.5),
                settings(30, continuous, mode, top),
                routes);
    }

    @Test
    void routesOnlyDrainsPoolAndExitsEvenWhenContinuousWithoutCollectingAnythingElse() {
        when(routes.processNext(0)).thenReturn(true, true, false);
        worker(true, CollectionMode.ROUTES_ONLY).run(null);
        InOrder order = inOrder(routes);
        order.verify(routes).initializePool();
        order.verify(routes, times(3)).processNext(0);
        verifyNoMoreInteractions(routes);
        verifyNoInteractions(games, users, metadata, sleeper);
    }

    @Test
    void routesOnlyStopsOnInterruptionWithoutStartingNormalCollection() {
        when(routes.processNext(0)).thenThrow(new CollectionInterruptedException());
        worker(false, CollectionMode.ROUTES_ONLY).run(null);
        verifyNoInteractions(games, users, metadata, sleeper);
    }

    @Test
    void routesOnlyPassesTheTopBoundaryAndNeverUsesUnboundedProcessing() {
        when(routes.processNext(995)).thenReturn(true, false);
        worker(false, CollectionMode.ROUTES_ONLY, 995).run(null);
        verify(routes).initializePool();
        verify(routes, times(2)).processNext(995);
        verifyNoMoreInteractions(routes);
        verifyNoInteractions(games, users, metadata, sleeper);
    }

    @Test
    void drainsGamesThenDiscoversUserGamesThenReturnsToGamePriorityWithoutFixedSleep() {
        when(games.processNext()).thenReturn(true, false, true, false);
        when(users.collectNext()).thenReturn(true, false);
        worker(false, CollectionMode.FULL, 995).run(null);
        InOrder order = inOrder(metadata, games, users, routes);
        order.verify(routes).initializePool();
        order.verify(metadata).refreshIfDue();
        order.verify(games).processNext();
        order.verify(routes).processBatch();
        order.verify(metadata).refreshIfDue();
        order.verify(games).processNext();
        order.verify(users).collectNext();
        order.verify(metadata).refreshIfDue();
        order.verify(games).processNext();
        order.verify(routes).processBatch();
        order.verify(metadata).refreshIfDue();
        order.verify(games).processNext();
        order.verify(users).collectNext();
        verifyNoInteractions(sleeper);
    }

    @Test
    void continuousModeSleepsOnlyWhenNoUserAndStopsOnInterruption() {
        doNothing()
                .doThrow(new CollectionInterruptedException())
                .when(sleeper)
                .sleep(Duration.ofMinutes(5));
        worker(true).run(null);
        verify(sleeper, times(2)).sleep(Duration.ofMinutes(5));
        verify(users, times(2)).collectNext();
    }

    @Test
    void gameProcessingInterruptionStopsBeforeDiscoveringUsers() {
        when(games.processNext()).thenThrow(new CollectionInterruptedException());
        worker(false).run(null);
        verifyNoInteractions(users, sleeper);
    }
}
