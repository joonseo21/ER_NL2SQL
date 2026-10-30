package io.eranalytics.pipeline.webapi.service;

import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** One admitted question per Spring process. The receipt ID owns the reservation. */
@Component
@ConditionalOnProperty(name = "er.web-api.enabled", havingValue = "true")
public class QuestionAdmissionGate {
    private final AtomicReference<String> activeRequest = new AtomicReference<>();
    private volatile boolean ready;

    public void ready() {
        ready = true;
    }

    public void acquire(String requestId) {
        if (!ready) {
            throw new WebApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "STORAGE_BUSY",
                    "요청이 겹쳐 처리하지 못했습니다. 잠시 뒤 다시 요청해 주세요.");
        }
        if (!activeRequest.compareAndSet(null, requestId)) {
            throw new WebApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "QUESTION_BUSY",
                    "지금 다른 질문을 처리하고 있습니다. 잠시 뒤 다시 질문해 주세요. 이번 시도는 질문 횟수에 포함되지 않습니다.");
        }
    }

    public void release(String requestId) {
        activeRequest.compareAndSet(requestId, null);
    }

    public boolean isOccupied() {
        return activeRequest.get() != null;
    }
}
