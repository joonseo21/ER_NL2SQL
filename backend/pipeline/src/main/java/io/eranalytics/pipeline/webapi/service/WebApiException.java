package io.eranalytics.pipeline.webapi.service;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class WebApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final Integer remainingToday;

    public WebApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public WebApiException(HttpStatus status, String code, String message, Integer remainingToday) {
        super(message);
        this.status = status;
        this.code = code;
        this.remainingToday = remainingToday;
    }

    public static WebApiException invalidInvite() {
        return new WebApiException(HttpStatus.UNAUTHORIZED, "INVITE_INVALID", "초대 코드를 확인해 주세요.");
    }
}
