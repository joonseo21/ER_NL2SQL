package io.eranalytics.pipeline.collection.route;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** Native pool ranking/counting and transactional persistence are owned by the DB adapter. */
public interface RouteStore {
    void initializePool();

    Map<RouteKey, Long> gameUsage(long gameId);

    void registerGame(long gameId, Map<RouteKey, Long> previous);

    Optional<RouteKey> claimNext(Instant now);

    void save(RouteKey key, RouteSnapshot snapshot, Instant now);

    void fail(RouteKey key, String status, Integer responseCode, Instant now);
}
