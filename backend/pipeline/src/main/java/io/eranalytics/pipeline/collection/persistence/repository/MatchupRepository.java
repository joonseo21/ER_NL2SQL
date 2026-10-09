package io.eranalytics.pipeline.collection.persistence.repository;

import io.eranalytics.pipeline.collection.persistence.entity.MatchupEntity;
import io.eranalytics.pipeline.collection.persistence.entity.MatchupId;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MatchupRepository extends JpaRepository<MatchupEntity, MatchupId> {
    List<MatchupEntity> findAllByIdParticipantIdIn(Collection<Long> participantIds);
}
