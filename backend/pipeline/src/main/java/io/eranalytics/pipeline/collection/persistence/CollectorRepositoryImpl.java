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
    public void enqueueGame(long gameId) {
        if (gameId <= 0) throw new IllegalArgumentException("Game id must be positive");
        jdbc.update("""
                INSERT INTO collect_queue(job_type, target_key)
                VALUES ('GAME', ?)
                ON CONFLICT (job_type, target_key) DO NOTHING
                """, Long.toString(gameId));
    }

    @Override
    public boolean gameExists(long gameId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM games WHERE game_id = ?)", Boolean.class, gameId));
    }

    @Override
    @Transactional
    public Optional<QueueJob> claimNextGame() {
        // A process restart cannot grant a sixth attempt to an exhausted pending job.
        jdbc.update("""
                UPDATE collect_queue SET status = 'FAILED', updated_at = now(),
                  last_error = coalesce(last_error, 'Attempt budget exhausted before restart')
                WHERE job_type = 'GAME' AND status IN ('PENDING', 'RETRY') AND attempts >= 5
                """);
        List<QueueJob> jobs = jdbc.query("""
                SELECT id, job_type, target_key, attempts
                FROM collect_queue
                WHERE job_type = 'GAME' AND status IN ('PENDING', 'RETRY') AND attempts < 5
                ORDER BY created_at, id
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
    public void recordAttempt(QueueJob job, int attempt) {
        jdbc.update("""
                UPDATE collect_queue
                SET attempts = ?, updated_at = now()
                WHERE id = ?
                """, attempt, job.id());
    }

    @Override
    @Transactional
    public void completeGame(QueueJob job, List<JsonNode> results) {
        if (!"GAME".equals(job.jobType()) || results.isEmpty()
                || !results.getFirst().path("gameId").isIntegralNumber()
                || !results.getFirst().path("gameId").canConvertToLong()
                || results.getFirst().get("gameId").longValue() != Long.parseLong(job.targetKey())) {
            throw new IllegalArgumentException("Game response does not match queue target");
        }
        upsertGame(results);
        jdbc.update("DELETE FROM collect_queue WHERE id = ? AND job_type = 'GAME'", job.id());
    }

    @Override
    public void markFailure(QueueJob job, String error, boolean retryable) {
        String status = retryable && job.attempts() < 5 ? "RETRY" : "FAILED";
        jdbc.update("""
                UPDATE collect_queue
                SET status = ?, last_error = ?, attempts = ?, updated_at = now()
                WHERE id = ?
                """, status, abbreviate(error, 2000), job.attempts(), job.id());
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
                  name_ko = coalesce(EXCLUDED.name_ko, characters.name_ko),
                  name_en = coalesce(EXCLUDED.name_en, characters.name_en)
                """, code, nameKo, nameEn);
    }

    private static String abbreviate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
