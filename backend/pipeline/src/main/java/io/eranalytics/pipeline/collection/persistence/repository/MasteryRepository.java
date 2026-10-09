package io.eranalytics.pipeline.collection.persistence.repository;

import io.eranalytics.pipeline.collection.persistence.entity.MasteryEntity;
import io.eranalytics.pipeline.collection.persistence.entity.MasteryId;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MasteryRepository extends JpaRepository<MasteryEntity, MasteryId> {
    List<MasteryEntity> findAllByIdParticipantIdIn(Collection<Long> participantIds);
}
