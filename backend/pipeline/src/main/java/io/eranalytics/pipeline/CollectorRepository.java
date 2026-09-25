package io.eranalytics.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class CollectorRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    CollectorRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    void upsertRankerAndQueue(String userId, String nickname, Integer rank, Integer mmr, int seasonId) {
        jdbc.update("""
                INSERT INTO rankers(uid, nickname, rank, mmr, season_id, fetched_at)
                VALUES (?, ?, ?, ?, ?, now())
                ON CONFLICT (uid) DO UPDATE SET
                  nickname = EXCLUDED.nickname, rank = EXCLUDED.rank, mmr = EXCLUDED.mmr,
                  season_id = EXCLUDED.season_id, fetched_at = now()
                """, userId, nickname, rank, mmr, seasonId);
        enqueueRefreshableUser(userId);
    }

    private void enqueueRefreshableUser(String userId) {
        jdbc.update("""
                INSERT INTO collect_queue(job_type, target_key)
                VALUES ('USER', ?)
                ON CONFLICT (job_type, target_key) DO UPDATE SET
                  status = 'PENDING', attempts = 0, last_error = NULL, updated_at = now()
                """, userId);
    }

    void enqueue(String jobType, String targetKey) {
        jdbc.update("""
                INSERT INTO collect_queue(job_type, target_key)
                VALUES (?, ?)
                ON CONFLICT (job_type, target_key) DO NOTHING
                """, jobType, targetKey);
    }

    @Transactional
    Optional<QueueJob> claimNext() {
        List<QueueJob> jobs = jdbc.query("""
                SELECT id, job_type, target_key, attempts
                FROM collect_queue
                WHERE status IN ('PENDING', 'RETRY')
                ORDER BY CASE job_type WHEN 'GAME' THEN 0 ELSE 1 END, created_at
                LIMIT 1
                FOR UPDATE SKIP LOCKED
                """, (rs, rowNum) -> new QueueJob(
                rs.getLong("id"), rs.getString("job_type"), rs.getString("target_key"),
                rs.getInt("attempts") + 1));
        if (jobs.isEmpty()) {
            return Optional.empty();
        }
        QueueJob job = jobs.getFirst();
        jdbc.update("UPDATE collect_queue SET attempts = ?, updated_at = now() WHERE id = ?",
                job.attempts(), job.id());
        return Optional.of(job);
    }

    void markDone(long id) {
        jdbc.update("""
                UPDATE collect_queue
                SET status = 'DONE', last_error = NULL, updated_at = now()
                WHERE id = ?
                """, id);
    }

    void markFailure(QueueJob job, String error, boolean retryable) {
        String status = retryable && job.attempts() < 5 ? "RETRY" : "FAILED";
        jdbc.update("""
                UPDATE collect_queue
                SET status = ?, last_error = ?, updated_at = now()
                WHERE id = ?
                """, status, abbreviate(error, 2000), job.id());
    }

    @Transactional
    void upsertGame(List<JsonNode> results) {
        if (results.isEmpty()) {
            throw new IllegalArgumentException("Game response contained no participants");
        }
        BattleUserResultMapper.GameRow game = BattleUserResultMapper.game(results.getFirst());
        jdbc.update("""
                INSERT INTO games(game_id, season_id, matching_mode, matching_team_mode,
                  version_season, version_major, version_minor, start_dtm, server_name, fetched_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (game_id) DO UPDATE SET
                  season_id = EXCLUDED.season_id, matching_mode = EXCLUDED.matching_mode,
                  matching_team_mode = EXCLUDED.matching_team_mode,
                  version_season = EXCLUDED.version_season, version_major = EXCLUDED.version_major,
                  version_minor = EXCLUDED.version_minor, start_dtm = EXCLUDED.start_dtm,
                  server_name = EXCLUDED.server_name, fetched_at = now()
                """, game.gameId(), game.seasonId(), game.matchingMode(), game.matchingTeamMode(),
                game.versionSeason(), game.versionMajor(), game.versionMinor(), game.startDtm(),
                game.serverName());

        for (JsonNode result : results) {
            BattleUserResultMapper.ParticipantRow participant = BattleUserResultMapper.participant(result);
            jdbc.update("""
                    INSERT INTO participants(game_id, nickname, team_number, character_num,
                      best_weapon, best_weapon_level, game_rank, player_kill, player_assistant,
                      monster_kill, damage_to_player, mmr_before, mmr_gain, mmr_after, play_time,
                      is_ranker, raw)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                      EXISTS (SELECT 1 FROM rankers WHERE nickname = ?), ?::jsonb)
                    ON CONFLICT (game_id, nickname) DO UPDATE SET
                      team_number = EXCLUDED.team_number, character_num = EXCLUDED.character_num,
                      best_weapon = EXCLUDED.best_weapon, best_weapon_level = EXCLUDED.best_weapon_level,
                      game_rank = EXCLUDED.game_rank, player_kill = EXCLUDED.player_kill,
                      player_assistant = EXCLUDED.player_assistant, monster_kill = EXCLUDED.monster_kill,
                      damage_to_player = EXCLUDED.damage_to_player, mmr_before = EXCLUDED.mmr_before,
                      mmr_gain = EXCLUDED.mmr_gain, mmr_after = EXCLUDED.mmr_after,
                      play_time = EXCLUDED.play_time, is_ranker = EXCLUDED.is_ranker, raw = EXCLUDED.raw
                    """, participant.gameId(), participant.nickname(), participant.teamNumber(),
                    participant.characterNum(), participant.bestWeapon(), participant.bestWeaponLevel(),
                    participant.gameRank(), participant.playerKill(), participant.playerAssistant(),
                    participant.monsterKill(), participant.damageToPlayer(), participant.mmrBefore(),
                    participant.mmrGain(), participant.mmrAfter(), participant.playTime(),
                    participant.nickname(), toJson(participant.raw()));
        }
    }

    void logApiCall(String endpoint, int statusCode, long latencyMs) {
        jdbc.update("""
                INSERT INTO api_call_log(endpoint, status_code, latency_ms)
                VALUES (?, ?, ?)
                """, endpoint, statusCode, Math.max(0, latencyMs));
    }

    void upsertCharacter(int code, String nameKo, String nameEn) {
        jdbc.update("""
                INSERT INTO characters(character_code, name_ko, name_en)
                VALUES (?, ?, ?)
                ON CONFLICT (character_code) DO UPDATE SET
                  name_ko = EXCLUDED.name_ko, name_en = EXCLUDED.name_en
                """, code, nameKo, nameEn);
    }

    private String toJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Could not serialize participant raw JSON", exception);
        }
    }

    private static Integer nullableInt(JsonNode node, String field) {
        return node.hasNonNull(field) && node.get(field).canConvertToInt()
                ? node.get(field).intValue()
                : null;
    }

    private static String abbreviate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
