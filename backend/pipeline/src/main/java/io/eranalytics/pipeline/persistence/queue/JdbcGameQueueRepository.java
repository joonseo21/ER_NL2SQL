package io.eranalytics.pipeline.persistence.queue;

import io.eranalytics.pipeline.collection.game.GameCollectionJob;
import io.eranalytics.pipeline.collection.game.GameQueueRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcGameQueueRepository implements GameQueueRepository {
    private final JdbcTemplate jdbc;

    public JdbcGameQueueRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void enqueueGame(long gameId) {
        if (gameId <= 0) throw new IllegalArgumentException("Game id must be positive");
        jdbc.update(
                """
                INSERT INTO collect_queue(job_type, target_key)
                VALUES ('GAME', ?)
                ON CONFLICT (job_type, target_key) DO NOTHING
                """,
                Long.toString(gameId));
    }

    @Override
    @Transactional
    public Optional<GameCollectionJob> claimNextGame() {
        // A process restart cannot grant a sixth attempt to an exhausted pending job.
        jdbc.update(
                """
                UPDATE collect_queue SET status = 'FAILED', updated_at = now(),
                  last_error = coalesce(last_error, 'Attempt budget exhausted before restart')
                WHERE job_type = 'GAME' AND status IN ('PENDING', 'RETRY') AND attempts >= 5
                """);
        List<GameCollectionJob> jobs =
                jdbc.query(
                        """
                SELECT id, job_type, target_key, attempts
                FROM collect_queue
                WHERE job_type = 'GAME' AND status IN ('PENDING', 'RETRY') AND attempts < 5
                ORDER BY created_at, id
                LIMIT 1
                FOR UPDATE SKIP LOCKED
                """,
                        (rs, rowNum) ->
                                new GameCollectionJob(
                                        rs.getLong("id"),
                                        rs.getString("job_type"),
                                        rs.getString("target_key"),
                                        rs.getInt("attempts") + 1));
        if (jobs.isEmpty()) {
            return Optional.empty();
        }
        GameCollectionJob job = jobs.getFirst();
        jdbc.update(
                "UPDATE collect_queue SET attempts = ?, updated_at = now() WHERE id = ?",
                job.attempts(),
                job.id());
        return Optional.of(job);
    }

    @Override
    public void recordAttempt(GameCollectionJob job, int attempt) {
        jdbc.update(
                """
                UPDATE collect_queue
                SET attempts = ?, updated_at = now()
                WHERE id = ?
                """,
                attempt,
                job.id());
    }

    @Override
    public void markFailure(GameCollectionJob job, String error, boolean retryable) {
        String status = retryable && job.attempts() < 5 ? "RETRY" : "FAILED";
        jdbc.update(
                """
                UPDATE collect_queue
                SET status = ?, last_error = ?, attempts = ?, updated_at = now()
                WHERE id = ?
                """,
                status,
                abbreviate(error, 2000),
                job.attempts(),
                job.id());
    }

    private static String abbreviate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
