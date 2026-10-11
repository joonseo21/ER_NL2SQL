package io.eranalytics.pipeline.persistence.repository;

import io.eranalytics.pipeline.persistence.entity.GameEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GameRepository extends JpaRepository<GameEntity, Long> {}
