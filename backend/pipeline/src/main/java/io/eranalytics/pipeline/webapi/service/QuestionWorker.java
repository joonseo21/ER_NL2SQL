package io.eranalytics.pipeline.webapi.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.eranalytics.pipeline.webapi.client.AgentClient;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/** Invoked through a separate Spring proxy after receipt commit, with no caller transaction. */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "er.web-api.enabled", havingValue = "true")
public class QuestionWorker {
    private static final Logger log = LoggerFactory.getLogger(QuestionWorker.class);
    private final AgentClient agent;
    private final QuestionProcessingStore store;

    @Async("webApiQuestionExecutor")
    public void process(String id) {
        try {
            QuestionProcessingStore.Work work = store.claim(id);
            if (work == null) return;
            ObjectNode response = agent.query(work.question(), work.conditions());
            saveResult(id, response);
        } catch (WebApiException failure) {
            fail(id, failure.getCode(), failure.getMessage());
        } catch (RuntimeException failure) {
            fail(
                    id,
                    QuestionFailure.QUESTION_PROCESSING_FAILED.name(),
                    QuestionFailure.QUESTION_PROCESSING_FAILED.getMessage());
        }
    }

    private void saveResult(String id, ObjectNode response) {
        try {
            store.complete(id, response);
        } catch (RuntimeException unavailable) {
            fail(
                    id,
                    QuestionFailure.RESULT_STORAGE_FAILED.name(),
                    QuestionFailure.RESULT_STORAGE_FAILED.getMessage());
        }
    }

    private void fail(String id, String code, String message) {
        try {
            store.fail(id, code, message);
        } catch (RuntimeException unavailable) {
            log.error("Question state update failed for request {} ({})", id, code);
        }
    }
}
