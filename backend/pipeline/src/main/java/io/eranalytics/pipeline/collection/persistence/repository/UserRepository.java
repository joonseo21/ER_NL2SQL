package io.eranalytics.pipeline.collection.persistence.repository;

import io.eranalytics.pipeline.collection.persistence.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<UserEntity, Long> {
    boolean existsBySeasonId(int seasonId);
    java.util.Optional<UserEntity> findBySeasonIdAndNickname(int seasonId, String nickname);
}
