package io.eranalytics.pipeline.webapi.repository;

import io.eranalytics.pipeline.webapi.entity.DailyUsageEntity;
import io.eranalytics.pipeline.webapi.entity.DailyUsageId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DailyUsageRepository extends JpaRepository<DailyUsageEntity, DailyUsageId> {}
