package io.eranalytics.pipeline.webapi.service;

import io.eranalytics.pipeline.webapi.dto.WebApiRequests;
import io.eranalytics.pipeline.webapi.entity.FeedbackEntity;
import io.eranalytics.pipeline.webapi.entity.QuestionLogEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "er.web-api.enabled", havingValue = "true")
public class FeedbackService {
    @PersistenceContext(unitName = "webapi")
    private EntityManager entities;

    private final WebApiClock webApiClock;

    @Transactional("webApiTransactionManager")
    public void save(String code, String logId, WebApiRequests.Feedback request) {
        QuestionLogEntity question =
                entities.find(QuestionLogEntity.class, logId, LockModeType.PESSIMISTIC_WRITE);
        if (question == null || !question.getInviteCode().equals(code)) {
            throw new WebApiException(
                    HttpStatus.NOT_FOUND, "QUESTION_NOT_FOUND", "후기를 남길 질문을 찾지 못했습니다.");
        }
        FeedbackEntity feedback = entities.find(FeedbackEntity.class, logId);
        boolean liked = "like".equals(request.rating());
        if (feedback == null) {
            entities.persist(
                    new FeedbackEntity(logId, liked, request.comment(), webApiClock.instant()));
        } else {
            feedback.update(liked, request.comment(), webApiClock.instant());
        }
    }
}
