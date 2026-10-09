package io.eranalytics.pipeline.collection.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import io.eranalytics.pipeline.collection.CollectorRepository;
import io.eranalytics.pipeline.collection.QueueJob;

import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class CollectorRepositoryImpl implements CollectorRepository {
    private final JdbcTemplate jdbc;
    private final GamePersistenceService gamePersistenceService;

    public CollectorRepositoryImpl(JdbcTemplate jdbc, GamePersistenceService gamePersistenceService) {
        this.jdbc = jdbc;
        this.gamePersistenceService = gamePersistenceService;
    }

    @Override
    @Transactional
    public void upsertRankerAndQueue(String userId, String nickname, Integer rank, Integer mmr, int seasonId) {
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

    @Override
    public void enqueue(String jobType, String targetKey) {
        jdbc.update("""
                INSERT INTO collect_queue(job_type, target_key)
                VALUES (?, ?)
                ON CONFLICT (job_type, target_key) DO NOTHING
                """, jobType, targetKey);
    }

    @Override
    @Transactional
    public Optional<QueueJob> claimNext() {
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

    @Override
    public void markDone(long id) {
        jdbc.update("""
                UPDATE collect_queue
                SET status = 'DONE', last_error = NULL, updated_at = now()
                WHERE id = ?
                """, id);
    }

    @Override
    public void markFailure(QueueJob job, String error, boolean retryable) {
        String status = retryable && job.attempts() < 5 ? "RETRY" : "FAILED";
        jdbc.update("""
                UPDATE collect_queue
                SET status = ?, last_error = ?, updated_at = now()
                WHERE id = ?
                """, status, abbreviate(error, 2000), job.id());
    }

    @Override
    public void upsertGame(List<JsonNode> results) {
        try {
            gamePersistenceService.store(results);
        } catch (DataAccessException exception) {
            // The runner logs the message; PostgreSQL constraint details may contain nicknames.
            throw new IllegalStateException("Game persistence failed", exception);
        }
    }

    @Override
    public void logApiCall(String endpoint, int statusCode, long latencyMs) {
        jdbc.update("""
                INSERT INTO api_call_log(endpoint, status_code, latency_ms)
                VALUES (?, ?, ?)
                """, endpoint, statusCode, Math.max(0, latencyMs));
    }

    @Override
    public void upsertCharacter(int code, String nameKo, String nameEn) {
        jdbc.update("""
                INSERT INTO characters(character_code, name_ko, name_en)
                VALUES (?, ?, ?)
                ON CONFLICT (character_code) DO UPDATE SET
                  name_ko = EXCLUDED.name_ko, name_en = EXCLUDED.name_en
                """, code, nameKo, nameEn);
    }

    private static String abbreviate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
