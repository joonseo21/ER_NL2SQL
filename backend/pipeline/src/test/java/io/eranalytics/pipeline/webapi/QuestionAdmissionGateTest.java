package io.eranalytics.pipeline.webapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.eranalytics.pipeline.webapi.service.QuestionAdmissionGate;
import io.eranalytics.pipeline.webapi.service.WebApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class QuestionAdmissionGateTest {
    @Test
    void onlyOneConcurrentRequestOwnsGateAndOnlyOwnerCanReleaseIt() throws Exception {
        QuestionAdmissionGate gate = new QuestionAdmissionGate();
        assertThatThrownBy(() -> gate.acquire("before-recovery"))
                .isInstanceOf(WebApiException.class)
                .satisfies(
                        error ->
                                assertThat(((WebApiException) error).getCode())
                                        .isEqualTo("STORAGE_BUSY"));
        gate.ready();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> attempts = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            for (int index = 0; index < 8; index++) {
                String id = "request-" + index;
                attempts.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    try {
                                        gate.acquire(id);
                                        return id;
                                    } catch (WebApiException busy) {
                                        assertThat(busy.getCode()).isEqualTo("QUESTION_BUSY");
                                        return null;
                                    }
                                }));
            }
            start.countDown();
            List<String> admitted = new ArrayList<>();
            for (Future<String> attempt : attempts) {
                String id = attempt.get();
                if (id != null) admitted.add(id);
            }
            assertThat(admitted).hasSize(1);
            gate.release("wrong-owner");
            assertThat(gate.isOccupied()).isTrue();
            gate.release(admitted.getFirst());
            assertThat(gate.isOccupied()).isFalse();
            gate.acquire("next");
            assertThat(gate.isOccupied()).isTrue();
        }
    }
}
