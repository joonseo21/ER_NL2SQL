package io.eranalytics.pipeline.collection;

import io.eranalytics.pipeline.collection.model.CrawlUser;
import io.eranalytics.pipeline.config.CollectionProperties;
import io.eranalytics.pipeline.erapi.ApiException;
import io.eranalytics.pipeline.erapi.ErApiClient;
import io.eranalytics.pipeline.erapi.dto.UserGamesPage;
import java.util.HashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class UserGameCrawler {
    private static final Logger log = LoggerFactory.getLogger(UserGameCrawler.class);
    private final ErApiClient api;
    private final CollectorRepository games;
    private final UserFrontierRepository frontier;
    private final CollectionProperties settings;
    private final CollectionRetry retry;

    public UserGameCrawler(ErApiClient api, CollectorRepository games, UserFrontierRepository frontier,
                           CollectionProperties settings, CollectionRetry retry) {
        this.api = api;
        this.games = games;
        this.frontier = frontier;
        this.settings = settings;
        this.retry = retry;
    }

    public void crawl(CrawlUser user) {
        String uid;
        try {
            uid = retry.call("nickname", () -> api.resolveUser(user.nickname()));
        } catch (CollectionInterruptedException exception) {
            throw exception;
        } catch (RuntimeException error) {
            frontier.fail(user, error instanceof ApiException apiError && apiError.getStatusCode() == 404,
                    CollectionRetry.reason(error));
            return;
        }
        try {
            collectPages(user, uid);
        } catch (CollectionInterruptedException exception) {
            throw exception;
        } catch (RuntimeException error) {
            frontier.fail(user, false, CollectionRetry.reason(error));
            log.warn("User frontier {} failed ({})", user.userId(), CollectionRetry.reason(error));
        }
    }

    private void collectPages(CrawlUser user, String uid) {
        Long cursor = null;
        Long newest = null;
        Long previousGameId = null;
        Set<Long> cursors = new HashSet<>();
        for (int pageNumber = 1; pageNumber <= settings.maxPagesPerUser(); pageNumber++) {
            Long requestedCursor = cursor;
            UserGamesPage page;
            try {
                page = retry.call("user games page", () -> api.userGames(uid, requestedCursor));
            } catch (ApiException error) {
                if (error.getStatusCode() != 404) throw error;
                frontier.complete(user, newest, true);
                return;
            }
            for (var game : page.userGames()) {
                if (previousGameId != null && game.gameId() >= previousGameId) {
                    throw new IllegalArgumentException("User games are not strictly newest first");
                }
                previousGameId = game.gameId();
                if (newest == null) newest = game.gameId();
                if (user.crawledNewestGameId() != null && game.gameId() <= user.crawledNewestGameId()) {
                    frontier.complete(user, newest, true);
                    return;
                }
                if (game.matchingMode() != 3 || game.matchingTeamMode() != 3) continue;
                if (game.seasonId() != user.seasonId() || game.versionMajor() < settings.minVersionMajor()) {
                    frontier.complete(user, newest, true);
                    return;
                }
                if (!games.gameExists(game.gameId())) games.enqueueGame(game.gameId());
            }
            if (page.next() == null) {
                frontier.complete(user, newest, true);
                return;
            }
            if (!cursors.add(page.next())) throw new IllegalArgumentException("Repeated user games cursor");
            cursor = page.next();
        }
        frontier.complete(user, newest, false);
        log.info("User frontier {} reached page limit {}; completion marker retained",
                user.userId(), settings.maxPagesPerUser());
    }
}
