package io.eranalytics.pipeline.collection.user;

import io.eranalytics.pipeline.collection.game.CollectedGameStore;
import io.eranalytics.pipeline.collection.game.GameQueueRepository;
import io.eranalytics.pipeline.collection.retry.CollectionRetryExecutor;
import io.eranalytics.pipeline.collection.runtime.CollectionInterruptedException;
import io.eranalytics.pipeline.config.CollectionProperties;
import io.eranalytics.pipeline.erapi.ErApiClient;
import io.eranalytics.pipeline.erapi.ErApiException;
import io.eranalytics.pipeline.erapi.dto.UserGameSummary;
import io.eranalytics.pipeline.erapi.dto.UserGamesPage;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class UserGameDiscovery {
    private static final Logger log = LoggerFactory.getLogger(UserGameDiscovery.class);
    private final ErApiClient api;
    private final GameQueueRepository queue;
    private final CollectedGameStore games;
    private final UserCollectionRepository frontier;
    private final CollectionProperties settings;
    private final CollectionRetryExecutor retry;

    public UserGameDiscovery(
            ErApiClient api,
            GameQueueRepository queue,
            CollectedGameStore games,
            UserCollectionRepository frontier,
            CollectionProperties settings,
            CollectionRetryExecutor retry) {
        this.api = api;
        this.queue = queue;
        this.games = games;
        this.frontier = frontier;
        this.settings = settings;
        this.retry = retry;
    }

    /**
     * 해당 사용자의 경기 목록 수집
     *
     * @param user
     */
    public void crawl(UserCollectionCandidate user) {
        String uid;
        try {
            uid = retry.call("nickname", () -> api.resolveUser(user.nickname()));
        } catch (CollectionInterruptedException exception) {
            throw exception;
        } catch (RuntimeException error) {
            frontier.fail(
                    user,
                    error instanceof ErApiException apiError && apiError.getStatusCode() == 404,
                    CollectionRetryExecutor.reason(error));
            return;
        }
        try {
            collectPages(user, uid);
        } catch (CollectionInterruptedException exception) {
            throw exception;
        } catch (RuntimeException error) {
            frontier.fail(user, false, CollectionRetryExecutor.reason(error));
            log.warn(
                    "User frontier {} failed ({})",
                    user.userId(),
                    CollectionRetryExecutor.reason(error));
        }
    }

    private void collectPages(UserCollectionCandidate user, String uid) {
        Long cursor = null;
        CrawlProgress progress = new CrawlProgress();
        Set<Long> cursors = new HashSet<>();
        for (int pageNumber = 1; pageNumber <= settings.maxPagesPerUser(); pageNumber++) {
            Long requestedCursor = cursor;
            UserGamesPage page;
            try {
                page = retry.call("user games page", () -> api.userGames(uid, requestedCursor));
            } catch (ErApiException error) {
                if (error.getStatusCode() != 404) throw error;
                frontier.complete(user, progress.newest, true);
                return;
            }
            if (scanPage(user, page.userGames(), progress)) {
                frontier.complete(user, progress.newest, true);
                return;
            }
            if (page.next() == null) {
                frontier.complete(user, progress.newest, true);
                return;
            }
            if (!cursors.add(page.next())) {
                throw new IllegalArgumentException("Repeated user games cursor");
            }
            cursor = page.next();
        }
        frontier.complete(user, progress.newest, false);
        log.info(
                "User frontier {} reached page limit {}; completion marker retained",
                user.userId(),
                settings.maxPagesPerUser());
    }

    private boolean scanPage(
            UserCollectionCandidate user, List<UserGameSummary> games, CrawlProgress progress) {
        for (UserGameSummary game : games) {
            validateOrder(progress.previousGameId, game.gameId());
            progress.previousGameId = game.gameId();
            if (progress.newest == null) progress.newest = game.gameId();
            if (user.crawledNewestGameId() != null && game.gameId() <= user.crawledNewestGameId()) {
                return true;
            }
            if (game.matchingMode() != 3 || game.matchingTeamMode() != 3) continue;
            if (outsideScope(user, game)) return true;
            if (!this.games.gameExists(game.gameId())) queue.enqueueGame(game.gameId());
        }
        return false;
    }

    private static void validateOrder(Long previousGameId, long gameId) {
        if (previousGameId != null && gameId >= previousGameId) {
            throw new IllegalArgumentException("User games are not strictly newest first");
        }
    }

    private boolean outsideScope(UserCollectionCandidate user, UserGameSummary game) {
        return game.seasonId() != user.seasonId()
                || game.versionMajor() < settings.minVersionMajor();
    }

    private static final class CrawlProgress {
        private Long newest;
        private Long previousGameId;
    }
}
