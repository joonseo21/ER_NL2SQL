package io.eranalytics.pipeline.collection.persistence.repository;

import io.eranalytics.pipeline.collection.persistence.entity.DeathEntity;
import io.eranalytics.pipeline.collection.persistence.entity.DeathId;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeathRepository extends JpaRepository<DeathEntity, DeathId> {
    List<DeathEntity> findAllByIdParticipantIdIn(Collection<Long> participantIds);
}
