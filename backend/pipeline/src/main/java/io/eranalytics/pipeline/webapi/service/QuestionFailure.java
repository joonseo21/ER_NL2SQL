package io.eranalytics.pipeline.webapi.service;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum QuestionFailure {
    AGENT_AUTH_FAILED("서비스 설정 문제로 질문을 처리하지 못했습니다. 운영자에게 알려 주세요."),
    AGENT_REQUEST_REJECTED("질문을 전달하지 못했습니다. 질문과 선택한 조건을 확인하고 다시 시도해 주세요."),
    AGENT_INTERNAL_ERROR("질문을 처리하는 중 오류가 발생했습니다. 잠시 뒤 다시 질문해 주세요."),
    AGENT_BUSY("질문 처리 서버가 아직 다른 질문을 처리 중입니다. 1~2분 뒤에 다시 질문해 주세요."),
    AGENT_CONNECTION_FAILED("질문 처리 서버에 연결하지 못했습니다. 잠시 뒤 다시 질문해 주세요."),
    AGENT_TIMEOUT("질문 처리 서버의 응답을 기다리는 시간이 끝났습니다. 처리가 아직 이어지고 있을 수 있으니 1~2분 뒤에 다시 질문해 주세요."),
    AGENT_HTTP_ERROR("질문 처리 서버의 응답을 확인할 수 없었습니다. 잠시 뒤 다시 질문해 주세요. 계속되면 운영자에게 알려 주세요."),
    AGENT_INVALID_RESPONSE("질문 처리 서버의 답변을 확인할 수 없었습니다. 잠시 뒤 다시 질문해 주세요. 계속되면 운영자에게 알려 주세요."),
    DISPATCH_UNAVAILABLE("질문을 접수했지만 처리를 시작하지 못했습니다. 잠시 뒤 다시 질문해 주세요."),
    RESULT_STORAGE_FAILED("질문의 답변을 저장하지 못했습니다. 잠시 뒤 새로 질문해 주세요. 계속되면 운영자에게 알려 주세요."),
    QUESTION_PROCESSING_FAILED("질문 처리에 실패했습니다. 잠시 뒤 다시 질문해 주세요. 계속되면 운영자에게 알려 주세요."),
    PROCESS_INTERRUPTED("서버가 다시 시작되어 질문 처리가 중단됐습니다. 잠시 뒤 새로 질문해 주세요.");

    private final String message;

    public WebApiException exception() {
        return new WebApiException(HttpStatus.BAD_GATEWAY, name(), message);
    }
}
