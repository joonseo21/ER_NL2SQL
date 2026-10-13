package io.eranalytics.pipeline.webapi.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.eranalytics.pipeline.webapi.repository.CollectionSummaryRepository;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Successful snapshots are kept for five minutes and never exposed as mutable shared JSON. */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "er.web-api.enabled", havingValue = "true")
public class CollectionSummaryService {
    private final CollectionSummaryRepository repository;
    private final WebApiClock clock;
    private ObjectNode cached;
    private Instant expiresAt;

    public synchronized ObjectNode summary() {
        Instant now = clock.instant();
        if (cached != null && now.isBefore(expiresAt)) return cached.deepCopy();
        try {
            ObjectNode snapshot = repository.snapshot();
            expiresAt = clock.instant().plus(Duration.ofMinutes(5));
            snapshot.put("snapshot_at", now.toString());
            snapshot.put("cached_until", expiresAt.toString());
            cached = snapshot;
            return snapshot.deepCopy();
        } catch (RuntimeException unavailable) {
            throw new WebApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "COLLECTION_UNAVAILABLE",
                    "수집 현황을 가져오지 못했습니다. 잠시 뒤 다시 확인해 주세요.");
        }
    }
}
