package io.eranalytics.pipeline.collection;

import io.eranalytics.pipeline.config.ErApiProperties;
import io.eranalytics.pipeline.erapi.ErApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class UserSeedBootstrap {
    private static final Logger log = LoggerFactory.getLogger(UserSeedBootstrap.class);
    private final ErApiClient api;
    private final UserFrontierRepository frontier;
    private final ErApiProperties settings;
    private final CollectionRetry retry;

    public UserSeedBootstrap(ErApiClient api, UserFrontierRepository frontier,
                             ErApiProperties settings, CollectionRetry retry) {
        this.api = api;
        this.frontier = frontier;
        this.settings = settings;
        this.retry = retry;
    }

    /**
     * 해당 시즌의 users가 비었을 때 랭킹 목록 전체로 첫 시드 생성
      */
    public boolean ensureUsers() {
        if (frontier.hasUsers(settings.seasonId())) return true;
        try {
            var seeds = retry.call("initial rank seeds", () -> api.topUsers(settings.seasonId()));
            frontier.seedIfEmpty(settings.seasonId(), seeds);
            log.info("Initial rank seeds processed: {}", seeds.size());
            return frontier.hasUsers(settings.seasonId());
        } catch (CollectionInterruptedException exception) {
            throw exception;
        } catch (RuntimeException error) {
            log.warn("Initial rank seeds failed ({})", CollectionRetry.reason(error));
            return false;
        }
    }
}
