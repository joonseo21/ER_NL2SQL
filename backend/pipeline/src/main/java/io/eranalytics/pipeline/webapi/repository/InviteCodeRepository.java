package io.eranalytics.pipeline.webapi.repository;

import io.eranalytics.pipeline.webapi.entity.InviteCodeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InviteCodeRepository extends JpaRepository<InviteCodeEntity, String> {}
