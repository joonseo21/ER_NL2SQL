package io.eranalytics.pipeline.collection.persistence;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** Transaction-scoped locks make find/create updates safe without changing V3/V4. */
@Repository
@RequiredArgsConstructor
public class GameWriteLock {
    private final EntityManager entityManager;

    public void acquire(String key) {
        // Select an integer instead of PostgreSQL's void result type.
        entityManager.createNativeQuery("""
                SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:key, 0))
                """, Integer.class).setParameter("key", key).getSingleResult();
    }
}
