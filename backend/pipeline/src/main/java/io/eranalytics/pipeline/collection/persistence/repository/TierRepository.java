package io.eranalytics.pipeline.collection.persistence.repository;

import io.eranalytics.pipeline.collection.persistence.entity.TierEntity;
import io.eranalytics.pipeline.collection.persistence.entity.TierId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TierRepository extends JpaRepository<TierEntity, TierId> {
    java.util.List<TierEntity> findByIdSeasonIdOrderByMinMmrDesc(int seasonId);
}
