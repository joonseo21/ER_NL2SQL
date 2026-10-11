package io.eranalytics.pipeline.persistence.repository;

import io.eranalytics.pipeline.persistence.entity.TraitEntity;
import io.eranalytics.pipeline.persistence.entity.TraitId;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TraitRepository extends JpaRepository<TraitEntity, TraitId> {
    List<TraitEntity> findAllByIdParticipantIdIn(Collection<Long> participantIds);
}
