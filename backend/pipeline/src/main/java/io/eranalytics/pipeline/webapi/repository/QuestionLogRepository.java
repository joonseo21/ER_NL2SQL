package io.eranalytics.pipeline.webapi.repository;

import io.eranalytics.pipeline.webapi.entity.QuestionLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuestionLogRepository extends JpaRepository<QuestionLogEntity, String> {}
