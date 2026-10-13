package io.eranalytics.pipeline.webapi.repository;

import io.eranalytics.pipeline.webapi.entity.FeedbackEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeedbackRepository extends JpaRepository<FeedbackEntity, String> {}
