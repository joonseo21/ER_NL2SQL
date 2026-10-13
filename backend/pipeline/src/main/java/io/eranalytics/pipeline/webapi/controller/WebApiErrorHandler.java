package io.eranalytics.pipeline.webapi.controller;

import io.eranalytics.pipeline.webapi.service.WebApiException;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "io.eranalytics.pipeline.webapi.controller")
@ConditionalOnProperty(name = "er.web-api.enabled", havingValue = "true")
public class WebApiErrorHandler {
    @ExceptionHandler(WebApiException.class)
    public ResponseEntity<Map<String, String>> application(WebApiException error) {
        return ResponseEntity.status(error.getStatus())
                .body(Map.of("code", error.getCode(), "message", error.getMessage()));
    }

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        HttpMessageNotReadableException.class
    })
    public ResponseEntity<Map<String, String>> validation(Exception error) {
        return ResponseEntity.badRequest()
                .body(Map.of("code", "VALIDATION_FAILED", "message", "요청 형식을 확인해 주세요."));
    }
}
