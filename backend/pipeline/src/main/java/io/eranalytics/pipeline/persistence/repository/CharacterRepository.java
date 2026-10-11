package io.eranalytics.pipeline.persistence.repository;

import io.eranalytics.pipeline.persistence.entity.CharacterEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CharacterRepository extends JpaRepository<CharacterEntity, Integer> {}
