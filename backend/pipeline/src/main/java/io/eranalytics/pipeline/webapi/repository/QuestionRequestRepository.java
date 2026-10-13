package io.eranalytics.pipeline.webapi.repository;

import io.eranalytics.pipeline.webapi.entity.QuestionRequestEntity;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuestionRequestRepository extends JpaRepository<QuestionRequestEntity, String> {
    long countByStatusIn(Collection<String> statuses);
}
