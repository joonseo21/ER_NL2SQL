package io.eranalytics.pipeline.webapi.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@ConditionalOnProperty(name = "er.web-api.enabled", havingValue = "true")
public class QuestionDispatcher {
    private static final Logger log = LoggerFactory.getLogger(QuestionDispatcher.class);
    private final QuestionWorker worker;
    private final QuestionProcessingStore store;

    public QuestionDispatcher(QuestionWorker worker, QuestionProcessingStore store) {
        this.worker = worker;
        this.store = store;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void accepted(QuestionAccepted event) {
        try {
            worker.process(event.requestId());
        } catch (RuntimeException rejected) {
            fail(event.requestId(), "DISPATCH_UNAVAILABLE", "질문을 접수했지만 처리 작업을 시작하지 못했습니다.");
        }
    }

    private void fail(String id, String code, String message) {
        try {
            store.fail(id, code, message);
        } catch (RuntimeException unavailable) {
            // Do not include exception contents: database errors can contain credentials or inputs.
            log.error("Question state update failed for request {} ({})", id, code);
        }
    }
}
