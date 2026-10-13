package io.eranalytics.pipeline.webapi.service;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum QuestionFailure {
    AGENT_AUTH_FAILED("질문 처리 서버의 연결 설정에 문제가 있습니다. 운영자에게 알려 주세요."),
    AGENT_REQUEST_REJECTED("질문 처리 서버가 요청 형식을 받아들이지 못했습니다. 질문과 조건을 확인해 주세요."),
    AGENT_INTERNAL_ERROR("질문 처리 서버에서 오류가 발생했습니다. 잠시 뒤 다시 이용해 주세요."),
    AGENT_BUSY("다른 질문을 처리 중입니다. 잠시 뒤 다시 질문해 주세요."),
    AGENT_CONNECTION_FAILED("질문 처리 서버에 연결하지 못했습니다. 잠시 뒤 다시 이용해 주세요."),
    AGENT_TIMEOUT("질문 처리 서버의 응답을 기다리는 시간이 끝났습니다. 자동으로 다시 요청하지 않습니다."),
    AGENT_HTTP_ERROR("질문 처리 서버에서 예상하지 못한 응답을 받았습니다. 운영자에게 알려 주세요."),
    AGENT_INVALID_RESPONSE("질문 처리 서버의 결과 형식을 확인하지 못했습니다. 운영자에게 알려 주세요."),
    DISPATCH_UNAVAILABLE("질문을 접수했지만 처리 작업을 시작하지 못했습니다. 잠시 뒤 다시 이용해 주세요."),
    RESULT_STORAGE_FAILED("처리 결과를 저장하지 못했습니다. 운영자에게 알려 주세요. 자동으로 다시 요청하지 않습니다."),
    QUESTION_PROCESSING_FAILED("질문 처리에 실패했습니다. 자동으로 다시 전송하지 않습니다.");

    private final String message;

    public WebApiException exception() {
        return new WebApiException(HttpStatus.BAD_GATEWAY, name(), message);
    }
}
