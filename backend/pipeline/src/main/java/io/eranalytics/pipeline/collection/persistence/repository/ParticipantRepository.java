package io.eranalytics.pipeline.collection.persistence.repository;

import io.eranalytics.pipeline.collection.persistence.entity.ParticipantEntity;
import io.eranalytics.pipeline.collection.persistence.entity.LatestUserGame;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ParticipantRepository extends JpaRepository<ParticipantEntity, Long> {
    List<ParticipantEntity> findByCoreGameId(long gameId);

    long countByUserId(long userId);

    @Query("""
            select new io.eranalytics.pipeline.collection.persistence.entity.LatestUserGame(
                p.core.mmrAfter, g.gameId, g.startDtm)
            from ParticipantEntity p join GameEntity g on g.gameId = p.core.gameId
            where p.userId = :userId
            order by g.startDtm desc nulls last, g.gameId desc
            """)
    List<LatestUserGame> findLatestGame(@Param("userId") long userId, Pageable page);
}
