package io.eranalytics.pipeline.webapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only projections use the web persistence unit, never collector entities. */
@Service
@ConditionalOnProperty(name = "er.web-api.enabled", havingValue = "true")
public class ConditionsService {
    @PersistenceContext(unitName = "webapi")
    private EntityManager entities;

    private final ObjectMapper json;
    private final int season;

    public ConditionsService(ObjectMapper json, @Value("${er.web-api.season-id:41}") int season) {
        this.json = json;
        this.season = season;
    }

    @Transactional(value = "webApiTransactionManager", readOnly = true)
    public ObjectNode list() {
        ObjectNode response = json.createObjectNode();
        ArrayNode patches = response.putArray("patches");
        for (Object[] row : patches()) {
            ObjectNode patch = patches.addObject();
            patch.put("major", ((Number) row[0]).intValue());
            patch.put("minor", ((Number) row[1]).intValue());
            patch.put("first_match", timestamp(row[2]));
            patch.put("last_match", timestamp(row[3]));
        }
        ArrayNode tiers = response.putArray("tiers");
        for (Object tier :
                entities.createNativeQuery(
                                "SELECT tier FROM tiers WHERE season_id=:season GROUP BY tier ORDER BY MIN(sort_order)")
                        .setParameter("season", season)
                        .getResultList()) {
            tiers.add(tier.toString());
        }
        ArrayNode characters = response.putArray("characters");
        for (Object row :
                entities.createNativeQuery(
                                "SELECT character_code, COALESCE(name_ko,name_en,character_code::text) FROM characters ORDER BY character_code")
                        .getResultList()) {
            Object[] values = (Object[]) row;
            characters
                    .addObject()
                    .put("code", ((Number) values[0]).intValue())
                    .put("name", (String) values[1]);
        }
        return response;
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> patches() {
        return entities.createNativeQuery(
                        """
                SELECT version_major,version_minor,MIN(start_dtm),MAX(start_dtm)
                FROM games WHERE season_id=:season AND matching_mode=3 AND matching_team_mode=3
                    AND version_major IS NOT NULL AND version_minor IS NOT NULL
                GROUP BY version_major,version_minor ORDER BY version_major DESC,version_minor DESC
                """)
                .setParameter("season", season)
                .getResultList();
    }

    private String timestamp(Object value) {
        if (value == null) return null;
        if (value instanceof Instant instant) return instant.toString();
        if (value instanceof OffsetDateTime date) return date.toInstant().toString();
        if (value instanceof java.sql.Timestamp date) return date.toInstant().toString();
        throw new IllegalStateException("Unsupported timestamp projection");
    }
}
