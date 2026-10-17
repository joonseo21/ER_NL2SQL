package io.eranalytics.pipeline.collection.runtime;

import static io.eranalytics.pipeline.collection.CollectionTestSettings.settings;
import static org.mockito.Mockito.*;

import io.eranalytics.pipeline.collection.game.GameCollectionProcessor;
import io.eranalytics.pipeline.collection.metadata.NameMetadataRefresher;
import io.eranalytics.pipeline.collection.route.RouteCollectionProcessor;
import io.eranalytics.pipeline.collection.user.UserCollectionCoordinator;
import io.eranalytics.pipeline.config.ErApiProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class CollectionWorkerTest {
    private final GameCollectionProcessor games = mock(GameCollectionProcessor.class);
    private final UserCollectionCoordinator users = mock(UserCollectionCoordinator.class);
    private final NameMetadataRefresher metadata = mock(NameMetadataRefresher.class);
    private final CollectionSleeper sleeper = mock(CollectionSleeper.class);
    private final RouteCollectionProcessor routes = mock(RouteCollectionProcessor.class);

    private CollectionWorker worker(boolean continuous) {
        return new CollectionWorker(
                games,
                users,
                metadata,
                sleeper,
                new ErApiProperties("https://example.invalid", "synthetic-key", 41, 0.5),
                settings(30, continuous),
                routes);
    }

    @Test
    void drainsGamesThenDiscoversUserGamesThenReturnsToGamePriorityWithoutFixedSleep() {
        when(games.processNext()).thenReturn(true, false, true, false);
        when(users.collectNext()).thenReturn(true, false);
        worker(false).run(null);
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
