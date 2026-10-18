package io.eranalytics.pipeline.persistence.metadata;

import io.eranalytics.pipeline.collection.metadata.MatchNameCodes;
import io.eranalytics.pipeline.collection.metadata.NameMatchUsageStore;
import io.eranalytics.pipeline.config.ErApiProperties;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Only fixed SQL identifiers are composed; all game/season/code/time values are bound. */
@Repository
public class PostgresNameMatchUsageStore implements NameMatchUsageStore {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final int seasonId;

    public PostgresNameMatchUsageStore(JdbcTemplate jdbc, ErApiProperties settings) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.seasonId = settings.seasonId();
    }

    private enum Kind {
        ITEM("items", "q.item_code", "JOIN participant_equipment q USING(participant_id)"),
        TRAIT("trait_names", "q.trait_code", "JOIN participant_traits q USING(participant_id)"),
        TACTICAL("tactical_skills", "p.tactical_skill_group", "");

        private final String table;
        private final String code;
        private final String child;

        Kind(String table, String code, String child) {
            this.table = table;
            this.code = code;
            this.child = child;
        }

        private String codes() {
            return "SELECT DISTINCT "
                    + code
                    + " FROM games g JOIN participants p USING(game_id) "
                    + child
                    + " WHERE g.season_id=? AND g.matching_mode=3 AND g.matching_team_mode=3";
        }

        private Set<Integer> from(MatchNameCodes codes) {
            return switch (this) {
                case ITEM -> codes.items();
                case TRAIT -> codes.traits();
                case TACTICAL -> codes.tacticalSkills();
            };
        }
    }

    @Override
    @Transactional
    public void initializeMissing(int season, Instant checkedAt) {
        if (season <= 0) throw new IllegalArgumentException("Usage season must be positive");
        for (Kind kind : Kind.values()) {
            String missing = "usage_season_id IS DISTINCT FROM ?";
            Boolean needed =
                    jdbc.queryForObject(
                            "SELECT EXISTS(SELECT FROM " + kind.table + " WHERE " + missing + ")",
                            Boolean.class,
                            season);
            if (!Boolean.TRUE.equals(needed)) continue;
            jdbc.update(
                    "UPDATE "
                            + kind.table
                            + " SET seen_in_matches=(code IN ("
                            + kind.codes()
                            + " AND "
                            + kind.code
                            + " IS NOT NULL))"
                            + ", usage_season_id=?, usage_checked_at=? WHERE "
                            + missing,
                    season,
                    season,
                    Timestamp.from(checkedAt),
                    season);
        }
    }

    @Override
    public MatchNameCodes gameCodes(long gameId) {
        return new MatchNameCodes(
                gameCodes(Kind.ITEM, gameId),
                gameCodes(Kind.TRAIT, gameId),
                gameCodes(Kind.TACTICAL, gameId));
    }

    private Set<Integer> gameCodes(Kind kind, long gameId) {
        return new HashSet<>(
                jdbc.queryForList(
                        kind.codes() + " AND g.game_id=? AND " + kind.code + " IS NOT NULL",
                        Integer.class,
                        seasonId,
                        gameId));
    }

    @Override
    @Transactional
    public void recordGame(long gameId, MatchNameCodes previous, Instant checkedAt) {
        MatchNameCodes current = gameCodes(gameId);
        for (Kind kind : Kind.values()) {
            Set<Integer> codes = new TreeSet<>(kind.from(previous));
            codes.addAll(kind.from(current));
            if (codes.isEmpty()) continue;
            // Stable locks keep replay removal and another game's insertion consistent.
            named.queryForList(
                    "SELECT code FROM "
                            + kind.table
                            + " WHERE code IN (:codes) ORDER BY code FOR UPDATE",
                    Map.of("codes", codes),
                    Integer.class);
            if (!kind.from(current).isEmpty()) {
                // One update per name category, rather than one round trip per used code.
                named.update(
                        "UPDATE "
                                + kind.table
                                + " SET seen_in_matches=true,usage_season_id=:season,usage_checked_at=:checked"
                                + " WHERE code IN (:codes) AND (usage_season_id IS DISTINCT FROM :season"
                                + " OR seen_in_matches IS DISTINCT FROM true)",
                        Map.of(
                                "season",
                                seasonId,
                                "checked",
                                Timestamp.from(checkedAt),
                                "codes",
                                kind.from(current)));
            }
            codes.removeAll(kind.from(current));
            for (int code : codes) {
                // Only a re-saved game's removed codes require a season-wide existence check.
                jdbc.update(
                        "UPDATE "
                                + kind.table
                                + " SET seen_in_matches=EXISTS("
                                + kind.codes()
                                + " AND "
                                + kind.code
                                + "=?),"
                                + " usage_season_id=?,usage_checked_at=? WHERE code=?",
                        seasonId,
                        code,
                        seasonId,
                        Timestamp.from(checkedAt),
                        code);
            }
        }
    }
}
