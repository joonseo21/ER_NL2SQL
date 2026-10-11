package io.eranalytics.pipeline.collection.game;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Game storage, including atomic completion of a successful queue job. */
public interface CollectedGameStore {
    boolean gameExists(long gameId);

    void upsertGame(List<JsonNode> results);

    void completeGame(GameCollectionJob job, List<JsonNode> results);
}
