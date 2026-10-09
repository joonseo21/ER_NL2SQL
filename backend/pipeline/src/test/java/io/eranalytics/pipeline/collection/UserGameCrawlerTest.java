package io.eranalytics.pipeline.collection;

import static io.eranalytics.pipeline.collection.CollectionTestSettings.settings;
import static org.mockito.Mockito.*;

import io.eranalytics.pipeline.collection.model.CrawlUser;
import io.eranalytics.pipeline.erapi.ApiException;
import io.eranalytics.pipeline.erapi.ErApiClient;
import io.eranalytics.pipeline.erapi.dto.UserGameSummary;
import io.eranalytics.pipeline.erapi.dto.UserGamesPage;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UserGameCrawlerTest {
    private final ErApiClient api = mock(ErApiClient.class);
    private final CollectorRepository games = mock(CollectorRepository.class);
    private final UserFrontierRepository frontier = mock(UserFrontierRepository.class);
    private final CollectionSleeper sleeper = mock(CollectionSleeper.class);
    private final CrawlUser user = new CrawlUser(1, 41, "synthetic_user", 100L);
    private UserGameCrawler crawler;

    @BeforeEach
    void prepare() {
        crawler = crawler(30);
        when(api.resolveUser(user.nickname())).thenReturn("uid");
    }

    private UserGameCrawler crawler(int pages) {
        return new UserGameCrawler(api, games, frontier, settings(pages, false), new CollectionRetry(sleeper));
    }

    private static UserGameSummary game(long id) { return new UserGameSummary(id, 41, 3, 3, 4); }
    private static UserGamesPage page(Long next, UserGameSummary... games) {
        return new UserGamesPage(List.of(games), next);
    }

    @Test
    void followsNextAndDoesNotStopAtGameStoredByAnotherUser() {
        when(api.userGames("uid", null)).thenReturn(page(180L, game(200), game(190)));
        when(games.gameExists(190)).thenReturn(true);
        when(api.userGames("uid", 180L)).thenReturn(page(null, game(170), game(100), game(90)));
        crawler.crawl(user);
        var order = inOrder(api, games, frontier);
        order.verify(api).resolveUser(user.nickname());
        order.verify(api).userGames("uid", null);
        order.verify(games).gameExists(200);
        order.verify(games).enqueueGame(200);
        order.verify(games).gameExists(190);
        order.verify(api).userGames("uid", 180L);
        order.verify(games).gameExists(170);
        order.verify(games).enqueueGame(170);
        order.verify(frontier).complete(user, 200L, true);
        verify(games, never()).enqueueGame(190);
        verify(games, never()).gameExists(100);
        verify(games, never()).gameExists(90);
    }

    @Test
    void page404IsNormalEndAndDoesNotRetryOrFailUser() {
        when(api.userGames("uid", null)).thenReturn(page(190L, game(200)));
        when(api.userGames("uid", 190L)).thenThrow(new ApiException(404, "not found"));
        crawler.crawl(user);
        verify(api).userGames("uid", 190L);
        verify(frontier).complete(user, 200L, true);
        verify(frontier, never()).fail(any(), anyBoolean(), anyString());
        verifyNoInteractions(sleeper);
    }

    @Test
    void nickname404IsNotFoundAndDoesNotRequestPages() {
        when(api.resolveUser(user.nickname())).thenThrow(new ApiException(404, "not found"));
        crawler.crawl(user);
        verify(frontier).fail(user, true, "ER API code 404");
        verify(api, never()).userGames(anyString(), any());
        verifyNoInteractions(sleeper);
    }

    @Test
    void pageLimitRetainsPriorMarker() {
        when(api.userGames("uid", null)).thenReturn(page(190L, game(200)));
        when(api.userGames("uid", 190L)).thenReturn(page(170L, game(180)));
        crawler(2).crawl(user);
        verify(frontier).complete(user, 200L, false);
        verify(api, never()).userGames("uid", 170L);
    }

    @Test
    void skipsOtherModesBeforeCheckingScopeAndRemembersFirstGame() {
        when(api.userGames("uid", null)).thenReturn(page(120L,
                new UserGameSummary(200L, 40, 2, 3, 2), game(190),
                new UserGameSummary(180L, 41, 3, 3, 3), game(170)));
        crawler.crawl(user);
        verify(games).enqueueGame(190);
        verify(games, never()).gameExists(200);
        verify(games, never()).gameExists(170);
        verify(frontier).complete(user, 200L, true);
        verify(api, never()).userGames("uid", 120L);
    }

    @Test
    void previousSeasonStopsBeforeEnqueue() {
        when(api.userGames("uid", null)).thenReturn(page(null, new UserGameSummary(200L, 40, 3, 3, 4)));
        crawler.crawl(user);
        verifyNoInteractions(games);
        verify(frontier).complete(user, 200L, true);
    }

    @Test
    void retriesOnlyFailingPageFiveTimesAndRecordsErrorWithoutAdvancingMarker() {
        when(api.userGames("uid", null)).thenReturn(page(190L, game(200)));
        when(api.userGames("uid", 190L)).thenThrow(new ApiException(429, "throttled"));
        crawler.crawl(user);
        verify(api).userGames("uid", null);
        verify(api, times(5)).userGames("uid", 190L);
        verify(frontier).fail(user, false, "ER API code 429");
        verify(frontier, never()).complete(any(), any(), anyBoolean());
    }

    @Test
    void repeatsAreErrorsAndCannotAdvanceCompletionMarker() {
        when(api.userGames("uid", null)).thenReturn(page(190L, game(200)));
        when(api.userGames("uid", 190L)).thenReturn(page(190L, game(180)));
        crawler.crawl(user);
        verify(frontier).fail(user, false, "IllegalArgumentException");
        verify(frontier, never()).complete(any(), any(), anyBoolean());
    }

    @Test
    void interruptionLeavesUserEligibleAndStopsCollection() {
        when(api.userGames("uid", null)).thenThrow(new CollectionInterruptedException());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> crawler.crawl(user))
                .isInstanceOf(CollectionInterruptedException.class);
        verifyNoInteractions(frontier);
    }
}
