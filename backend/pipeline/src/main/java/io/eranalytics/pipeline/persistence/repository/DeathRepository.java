package io.eranalytics.pipeline.persistence.repository;

import io.eranalytics.pipeline.persistence.entity.DeathEntity;
import io.eranalytics.pipeline.persistence.entity.DeathId;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeathRepository extends JpaRepository<DeathEntity, DeathId> {
    List<DeathEntity> findAllByIdParticipantIdIn(Collection<Long> participantIds);
}
