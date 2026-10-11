package io.eranalytics.pipeline.persistence.game;

import com.fasterxml.jackson.databind.JsonNode;
import io.eranalytics.pipeline.collection.game.CollectedGameStore;
import io.eranalytics.pipeline.collection.game.GameCollectionJob;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Game persistence and successful queue deletion share the same transaction. */
@Repository
public class PostgresCollectedGameStore implements CollectedGameStore {
    private final JdbcTemplate jdbc;
    private final GamePersistenceService gamePersistenceService;

    public PostgresCollectedGameStore(
            JdbcTemplate jdbc, GamePersistenceService gamePersistenceService) {
        this.jdbc = jdbc;
        this.gamePersistenceService = gamePersistenceService;
    }

    @Override
    public boolean gameExists(long gameId) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM games WHERE game_id = ?)",
                        Boolean.class,
                        gameId));
    }

    @Override
    @Transactional
    public void completeGame(GameCollectionJob job, List<JsonNode> results) {
        if (!"GAME".equals(job.jobType())
                || results.isEmpty()
                || !results.getFirst().path("gameId").isIntegralNumber()
                || !results.getFirst().path("gameId").canConvertToLong()
                || results.getFirst().get("gameId").longValue()
                        != Long.parseLong(job.targetKey())) {
            throw new IllegalArgumentException("Game response does not match queue target");
        }
        upsertGame(results);
        jdbc.update("DELETE FROM collect_queue WHERE id = ? AND job_type = 'GAME'", job.id());
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
}
