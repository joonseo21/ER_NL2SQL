package io.eranalytics.pipeline.webapi.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.eranalytics.pipeline.webapi.service.ConditionsService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Column-only projections in one database snapshot, including users' current tier. */
@Repository
@ConditionalOnProperty(name = "er.web-api.enabled", havingValue = "true")
public class CollectionSummaryRepository {
    @PersistenceContext(unitName = "webapi")
    private EntityManager entities;

    private final ObjectMapper json;
    private final ConditionsService conditions;
    private final int season;

    public CollectionSummaryRepository(
            ObjectMapper json,
            ConditionsService conditions,
            @Value("${er.web-api.season-id:${ER_SEASON_ID:41}}") int season) {
        this.json = json;
        this.conditions = conditions;
        this.season = season;
    }

    @Transactional(
            value = "webApiTransactionManager",
            readOnly = true,
            isolation = Isolation.REPEATABLE_READ)
    public ObjectNode snapshot() {
        Object[] total =
                (Object[])
                        entities.createNativeQuery(
                                        """
                SELECT COUNT(game_id),MAX(fetched_at) FROM games
                WHERE season_id=:season AND matching_mode=3 AND matching_team_mode=3
                """)
                                .setParameter("season", season)
                                .getSingleResult();
        ObjectNode result = json.createObjectNode();
        result.put("total_games", ((Number) total[0]).longValue());
        result.put("latest_collected_at", ConditionsService.timestamp(total[1]));
        result.set("patches", conditions.availablePatches());
        Map<String, ObjectNode> tiers = tierBuckets();
        addCounts(
                tiers,
                "users",
                "SELECT tier,COUNT(*) FROM users WHERE season_id=:season GROUP BY tier");
        addCounts(
                tiers,
                "participant_appearances",
                """
                SELECT p.tier,COUNT(p.participant_id) FROM participants p JOIN games g ON p.game_id=g.game_id
                WHERE g.season_id=:season AND g.matching_mode=3 AND g.matching_team_mode=3 GROUP BY p.tier
                """);
        ArrayNode byTier = result.putArray("by_tier");
        tiers.values().forEach(byTier::add);
        return result;
    }

    private Map<String, ObjectNode> tierBuckets() {
        Map<String, ObjectNode> result = new LinkedHashMap<>();
        for (Object tier :
                entities.createNativeQuery(
                                "SELECT tier FROM tiers WHERE season_id=:season GROUP BY tier ORDER BY MIN(sort_order)")
                        .setParameter("season", season)
                        .getResultList()) {
            result.put((String) tier, bucket((String) tier));
        }
        return result;
    }

    private void addCounts(Map<String, ObjectNode> tiers, String field, String sql) {
        for (Object row :
                entities.createNativeQuery(sql).setParameter("season", season).getResultList()) {
            Object[] values = (Object[]) row;
            tiers.computeIfAbsent((String) values[0], this::bucket)
                    .put(field, ((Number) values[1]).longValue());
        }
    }

    private ObjectNode bucket(String tier) {
        return json.createObjectNode()
                .put("tier", tier)
                .put("users", 0)
                .put("participant_appearances", 0);
    }
}
