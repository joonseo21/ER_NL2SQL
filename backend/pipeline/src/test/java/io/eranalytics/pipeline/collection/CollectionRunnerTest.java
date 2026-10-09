package io.eranalytics.pipeline.collection;

import static io.eranalytics.pipeline.collection.CollectionTestSettings.settings;
import static org.mockito.Mockito.*;

import io.eranalytics.pipeline.collection.model.CrawlUser;
import io.eranalytics.pipeline.config.ErApiProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CollectionRunnerTest {
    private final CollectorRepository games = mock(CollectorRepository.class);
    private final UserFrontierRepository frontier = mock(UserFrontierRepository.class);
    private final UserSeedBootstrap bootstrap = mock(UserSeedBootstrap.class);
    private final UserGameCrawler crawler = mock(UserGameCrawler.class);
    private final GameJobProcessor processor = mock(GameJobProcessor.class);
    private final CharacterMetadataCollector metadata = mock(CharacterMetadataCollector.class);
    private final CollectionSleeper sleeper = mock(CollectionSleeper.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-09T00:00:00Z"), ZoneOffset.UTC);
    private final OffsetDateTime before = OffsetDateTime.now(clock).minusHours(4);

    private CollectionRunner runner(boolean continuous) {
        return new CollectionRunner(games, frontier, bootstrap, crawler, processor, metadata, sleeper,
                new ErApiProperties("https://example.invalid", "synthetic-key", 41, 0.5),
                settings(30, continuous), clock);
    }

    @Test
    void drainsGameQueueThenCrawlsUserThenReturnsToGamePriorityWithoutFixedSleep() {
        QueueJob first = new QueueJob(1, "GAME", "200", 1);
        QueueJob next = new QueueJob(2, "GAME", "300", 1);
        CrawlUser user = new CrawlUser(1, 41, "synthetic_user", null);
        when(games.claimNextGame()).thenReturn(Optional.of(first), Optional.empty(),
                Optional.of(next), Optional.empty());
        when(bootstrap.ensureUsers()).thenReturn(true);
        when(frontier.selectNext(41, 4, 3600, before)).thenReturn(Optional.of(user), Optional.empty());
        runner(false).run(null);
        var order = inOrder(games, processor, bootstrap, frontier, crawler);
        order.verify(games).claimNextGame();
        order.verify(processor).process(first);
        order.verify(games).claimNextGame();
        order.verify(bootstrap).ensureUsers();
        order.verify(frontier).selectNext(41, 4, 3600, before);
        order.verify(crawler).crawl(user);
        order.verify(games).claimNextGame();
        order.verify(processor).process(next);
        verifyNoInteractions(sleeper);
    }

    @Test
    void continuousModeSleepsOnlyWhenNoUserAndStopsOnInterruption() {
        when(games.claimNextGame()).thenReturn(Optional.empty());
        when(bootstrap.ensureUsers()).thenReturn(true);
        when(frontier.selectNext(41, 4, 3600, before)).thenReturn(Optional.empty());
        doNothing().doThrow(new CollectionInterruptedException()).when(sleeper).sleep(Duration.ofMinutes(5));
        runner(true).run(null);
        verify(sleeper, times(2)).sleep(Duration.ofMinutes(5));
        verify(frontier, times(2)).selectNext(41, 4, 3600, before);
    }

    @Test
    void exhaustedJobDoesNotBlockFollowingJob() {
        QueueJob failed = new QueueJob(1, "GAME", "200", 1);
        QueueJob next = new QueueJob(2, "GAME", "300", 1);
        when(games.claimNextGame()).thenReturn(Optional.of(failed), Optional.of(next), Optional.empty());
        runner(false).run(null);
        var order = inOrder(processor);
        order.verify(processor).process(failed);
        order.verify(processor).process(next);
    }
}
