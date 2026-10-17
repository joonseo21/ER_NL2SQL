package io.eranalytics.pipeline.persistence.route;

import io.eranalytics.pipeline.collection.route.RouteKey;
import io.eranalytics.pipeline.collection.route.RouteSnapshot;
import io.eranalytics.pipeline.collection.route.RouteStore;
import io.eranalytics.pipeline.persistence.entity.RouteEntity;
import io.eranalytics.pipeline.persistence.entity.RouteId;
import io.eranalytics.pipeline.persistence.entity.RoutePathEntity;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class PostgresRouteStore implements RouteStore {
    private final JdbcTemplate jdbc;
    private final EntityManager entities;

    public PostgresRouteStore(JdbcTemplate jdbc, EntityManager entities) {
        this.jdbc = jdbc;
        this.entities = entities;
    }

    @Override
    @Transactional
    public void initializePool() {
        // An interrupted request is terminal: restart must not query the number a second time.
        jdbc.update("UPDATE routes SET fetch_status='INTERRUPTED' WHERE fetch_status='FETCHING'");
        jdbc.update("UPDATE routes SET usage_count=0");
        jdbc.update(
                """
                INSERT INTO routes(season_id, route_id, usage_count)
                SELECT g.season_id, p.route_id_of_start, count(*)
                FROM participants p JOIN games g USING(game_id)
                WHERE g.season_id IS NOT NULL AND p.route_id_of_start > 0
                GROUP BY g.season_id, p.route_id_of_start
                ON CONFLICT (season_id, route_id) DO UPDATE SET usage_count=EXCLUDED.usage_count
                """);
    }

    @Override
    public Map<RouteKey, Long> gameUsage(long gameId) {
        Map<RouteKey, Long> counts = new HashMap<>();
        jdbc.query(
                """
                SELECT g.season_id, p.route_id_of_start, count(*) AS uses
                FROM games g JOIN participants p USING(game_id)
                WHERE g.game_id=? AND p.route_id_of_start > 0
                GROUP BY g.season_id, p.route_id_of_start
                """,
                rs -> {
                    counts.put(new RouteKey(rs.getInt(1), rs.getInt(2)), rs.getLong(3));
                },
                gameId);
        return counts;
    }

    @Override
    public void registerGame(long gameId, Map<RouteKey, Long> previous) {
        Map<RouteKey, Long> changes = new HashMap<>(gameUsage(gameId));
        previous.forEach((key, count) -> changes.merge(key, -count, Long::sum));
        changes.forEach(
                (key, count) -> {
                    if (count == 0) return;
                    jdbc.update(
                            """
                    INSERT INTO routes(season_id, route_id, usage_count) VALUES (?,?,greatest(0,?))
                    ON CONFLICT (season_id,route_id) DO UPDATE
                    SET usage_count=greatest(0,routes.usage_count + ?)
                    """,
                            key.seasonId(),
                            key.routeId(),
                            count,
                            count);
                });
    }

    @Override
    @Transactional
    public Optional<RouteKey> claimNext(Instant now) {
        List<RouteKey> keys =
                jdbc.query(
                        """
                UPDATE routes SET fetch_status='FETCHING', fetched_at=?
                WHERE (season_id,route_id) = (
                    SELECT season_id,route_id FROM routes
                    WHERE fetch_status='PENDING' AND usage_count>0
                    ORDER BY usage_count DESC, season_id, route_id
                    LIMIT 1 FOR UPDATE SKIP LOCKED)
                RETURNING season_id,route_id
                """,
                        (rs, index) -> new RouteKey(rs.getInt(1), rs.getInt(2)),
                        Timestamp.from(now));
        return keys.stream().findFirst();
    }

    @Override
    @Transactional
    public void save(RouteKey key, RouteSnapshot snapshot, Instant now) {
        RouteId id = new RouteId(key.seasonId(), key.routeId());
        RouteEntity row = entities.find(RouteEntity.class, id);
        if (row == null) throw new IllegalStateException("Route must be claimed before saving");
        row.apply(snapshot, now);
        for (int index = 0; index < snapshot.paths().size(); index++) {
            entities.persist(
                    new RoutePathEntity(
                            key.seasonId(), key.routeId(), index + 1, snapshot.paths().get(index)));
        }
        entities.flush();
    }

    @Override
    @Transactional
    public void fail(RouteKey key, String status, Integer responseCode, Instant now) {
        jdbc.update(
                """
                UPDATE routes SET fetch_status=?, response_code=?, fetched_at=?
                WHERE season_id=? AND route_id=? AND fetch_status='FETCHING'
                """,
                status,
                responseCode,
                Timestamp.from(now),
                key.seasonId(),
                key.routeId());
    }
}
