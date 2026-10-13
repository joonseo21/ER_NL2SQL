package io.eranalytics.pipeline.webapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.eranalytics.pipeline.webapi.entity.QuestionLogEntity;
import io.eranalytics.pipeline.webapi.entity.QuestionRequestEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Each state change owns a new short transaction; remote calls never run inside it. */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "er.web-api.enabled", havingValue = "true")
public class QuestionProcessingStore {
    @PersistenceContext(unitName = "webapi")
    private EntityManager entities;

    private final WebApiClock clock;

    public record Work(String question, JsonNode conditions) {}

    @Transactional(value = "webApiTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public Work claim(String id) {
        QuestionRequestEntity request = locked(id);
        if (request == null || !"accepted".equals(request.getStatus())) return null;
        request.processing(clock.instant());
        return new Work(request.getQuestion(), request.getRequestConditions().deepCopy());
    }

    @Transactional(value = "webApiTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void complete(String id, ObjectNode response) {
        QuestionRequestEntity request = locked(id);
        if (request == null || !"processing".equals(request.getStatus())) return;
        entities.persist(
                QuestionLogEntity.builder()
                        .logId(response.required("log_id").asText())
                        .inviteCode(request.getInviteCode())
                        .question(request.getQuestion())
                        .requestConditions(request.getRequestConditions())
                        .responseType(response.required("kind").asText())
                        .appliedConditions(objectOrEmpty(response.get("applied_conditions")))
                        .sqlText(text(response, "sql"))
                        .columns(response.required("columns"))
                        .rows(response.required("rows"))
                        .summary(text(response, "summary"))
                        .message(text(response, "message"))
                        .warnings(response.required("warnings"))
                        .evidence(response.get("evidence"))
                        .elapsedMs(response.required("elapsed_ms").asLong())
                        .createdAt(clock.instant())
                        .build());
        // The FK points to a log inserted in the same transaction.
        entities.flush();
        request.complete(response, clock.instant());
    }

    @Transactional(value = "webApiTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void fail(String id, String code, String message) {
        QuestionRequestEntity request = locked(id);
        if (request == null
                || "completed".equals(request.getStatus())
                || "failed".equals(request.getStatus())) return;
        request.fail(code, message, clock.instant());
    }

    private QuestionRequestEntity locked(String id) {
        return entities.find(QuestionRequestEntity.class, id, LockModeType.PESSIMISTIC_WRITE);
    }

    private JsonNode objectOrEmpty(JsonNode value) {
        return value == null || value.isNull() ? JsonNodeFactory.instance.objectNode() : value;
    }

    private String text(ObjectNode response, String field) {
        JsonNode value = response.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
