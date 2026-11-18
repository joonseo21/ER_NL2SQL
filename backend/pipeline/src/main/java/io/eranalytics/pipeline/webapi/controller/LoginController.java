package io.eranalytics.pipeline.webapi.controller;

import com.fasterxml.jackson.databind.JsonNode;
import io.eranalytics.pipeline.webapi.service.LoginAccounts;
import io.eranalytics.pipeline.webapi.service.LoginService;
import io.eranalytics.pipeline.webapi.service.WebApiException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "er.web-api.enabled", havingValue = "true")
public class LoginController {
    private final LoginService login;

    @PostMapping("/api/auth/login")
    public Map<String, Object> login(@RequestBody JsonNode request) {
        String username = text(request, "username");
        String password = text(request, "password");
        if (!username.matches(LoginAccounts.USERNAME_PATTERN)
                || password.isEmpty()
                || password.length() > 200) {
            throw invalid();
        }
        return login.login(username, password);
    }

    private static String text(JsonNode request, String field) {
        JsonNode value = request.get(field);
        if (value == null || !value.isTextual()) throw invalid();
        return value.textValue();
    }

    private static WebApiException invalid() {
        return new WebApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "요청 형식을 확인해 주세요.");
    }
}
