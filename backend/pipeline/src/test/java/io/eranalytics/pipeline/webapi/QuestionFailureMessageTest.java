package io.eranalytics.pipeline.webapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.eranalytics.pipeline.webapi.service.QuestionFailure;
import org.junit.jupiter.api.Test;

class QuestionFailureMessageTest {
    @Test
    void failedMessagesExplainTheProblemAndNextActionWithoutInternalTermsOrQuotaClaims() {
        for (QuestionFailure failure : QuestionFailure.values()) {
            String message = failure.getMessage();
            assertThat(message).doesNotContain("LLM", "HTTP", "SQL", "횟수");
            assertThat(message)
                    .containsAnyOf("다시 질문해 주세요", "새로 질문해 주세요", "다시 시도해 주세요", "운영자에게 알려 주세요");
        }
        assertThat(QuestionFailure.AGENT_TIMEOUT.getMessage())
                .isEqualTo(
                        "질문 처리 서버의 응답을 기다리는 시간이 끝났습니다. 처리가 아직 이어지고 있을 수 있으니 1~2분 뒤에 다시 질문해 주세요.");
        assertThat(QuestionFailure.PROCESS_INTERRUPTED.getMessage())
                .contains("서버가 다시 시작되어", "새로 질문해 주세요");
    }
}
