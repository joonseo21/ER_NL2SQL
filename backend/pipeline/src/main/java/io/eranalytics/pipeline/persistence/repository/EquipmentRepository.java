package io.eranalytics.pipeline.persistence.repository;

import io.eranalytics.pipeline.persistence.entity.EquipmentEntity;
import io.eranalytics.pipeline.persistence.entity.EquipmentId;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EquipmentRepository extends JpaRepository<EquipmentEntity, EquipmentId> {
    List<EquipmentEntity> findAllByIdParticipantIdIn(Collection<Long> participantIds);
}
