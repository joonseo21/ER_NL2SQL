package io.eranalytics.pipeline.webapi.controller;

import io.eranalytics.pipeline.webapi.service.WebApiException;
import java.util.LinkedHashMap;
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
    public ResponseEntity<Map<String, Object>> application(WebApiException error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", error.getCode());
        body.put("message", error.getMessage());
        if (error.getRemainingToday() != null) {
            body.put("remaining_today", error.getRemainingToday());
        }
        return ResponseEntity.status(error.getStatus()).body(body);
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
