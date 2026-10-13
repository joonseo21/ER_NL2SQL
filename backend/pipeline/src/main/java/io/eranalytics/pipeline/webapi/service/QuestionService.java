package io.eranalytics.pipeline.webapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.eranalytics.pipeline.webapi.dto.WebApiRequests;
import io.eranalytics.pipeline.webapi.entity.QuestionRequestEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "er.web-api.enabled", havingValue = "true")
public class QuestionService {
    @PersistenceContext(unitName = "webapi")
    private EntityManager entities;

    private final UsageService usage;
    private final ApplicationEventPublisher events;
    private final WebApiClock webApiClock;

    public ObjectNode ask(String code, WebApiRequests.Question request) {
        JsonNode conditions = RequestConditions.validate(request.conditions());
        return usage.withQuestion(
                code,
                remaining -> {
                    String id = UUID.randomUUID().toString();
                    entities.persist(
                            new QuestionRequestEntity(
                                    id,
                                    code,
                                    request.question(),
                                    conditions,
                                    webApiClock.instant()));
                    events.publishEvent(new QuestionAccepted(id));
                    ObjectNode response =
                            com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                                    .objectNode();
                    response.put("request_id", id);
                    response.put("status", "accepted");
                    response.put("message", "질문을 접수했습니다. 처리 결과는 잠시 뒤 확인해 주세요.");
                    response.put("remaining_today", remaining);
                    return response;
                });
    }

    @Transactional(value = "webApiTransactionManager", readOnly = true)
    public ObjectNode find(String code, String requestId) {
        QuestionRequestEntity request = entities.find(QuestionRequestEntity.class, requestId);
        if (request == null || !request.getInviteCode().equals(code)) {
            throw new WebApiException(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    "QUESTION_NOT_FOUND",
                    "접수한 질문을 찾지 못했습니다.");
        }
        ObjectNode response =
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        response.put("request_id", requestId);
        response.put("status", request.getStatus());
        response.set("result", request.getResponse());
        response.put("error_code", request.getErrorCode());
        response.put("message", request.getMessage());
        return response;
    }
}
