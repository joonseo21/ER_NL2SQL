package io.eranalytics.pipeline.webapi;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.eranalytics.pipeline.webapi.config.InviteCodeFilter;
import io.eranalytics.pipeline.webapi.controller.LoginController;
import io.eranalytics.pipeline.webapi.controller.WebApiController;
import io.eranalytics.pipeline.webapi.controller.WebApiErrorHandler;
import io.eranalytics.pipeline.webapi.service.ConditionsService;
import io.eranalytics.pipeline.webapi.service.FeedbackService;
import io.eranalytics.pipeline.webapi.service.InviteService;
import io.eranalytics.pipeline.webapi.service.LoginAccounts;
import io.eranalytics.pipeline.webapi.service.LoginService;
import io.eranalytics.pipeline.webapi.service.PasswordHashes;
import io.eranalytics.pipeline.webapi.service.QuestionService;
import io.eranalytics.pipeline.webapi.service.WebApiClock;
import io.eranalytics.pipeline.webapi.service.WebApiException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LoginTest {
    private static final String HASH = PasswordHashes.create("correct".toCharArray());
    private final ObjectMapper json = new ObjectMapper();
    private final InviteService invites = mock(InviteService.class);
    private final WebApiClock clock = mock(WebApiClock.class);
    private final Instant start = Instant.parse("2026-11-18T12:00:00Z");
    @TempDir Path directory;
    private LoginService service;
    private MockMvc http;

    @BeforeEach
    void setup() throws Exception {
        when(clock.instant()).thenReturn(start);
        when(invites.remaining("synthetic-code")).thenReturn(42);
        when(invites.requireActive("synthetic-code")).thenReturn("synthetic-code");
        install(accounts(List.of(account("demo_user"))));
    }

    private Map<String, String> account(String username) {
        return Map.of("username", username, "password_hash", HASH, "invite_code", "synthetic-code");
    }

    private LoginAccounts accounts(Object value) throws Exception {
        Path file = directory.resolve("accounts.json");
        json.writeValue(file.toFile(), value);
        return new LoginAccounts(file.toString(), json);
    }

    private void install(LoginAccounts accounts) {
        service = new LoginService(accounts, invites, clock);
        ConditionsService conditions = mock(ConditionsService.class);
        when(conditions.list()).thenReturn(json.createObjectNode());
        http =
                MockMvcBuilders.standaloneSetup(
                                new LoginController(service),
                                new WebApiController(
                                        invites,
                                        conditions,
                                        mock(QuestionService.class),
                                        mock(FeedbackService.class)))
                        .setControllerAdvice(new WebApiErrorHandler())
                        .addFilters(new InviteCodeFilter(invites, json))
                        .build();
    }

    private String request(String username, String password) throws Exception {
        return json.writeValueAsString(Map.of("username", username, "password", password));
    }

    private String failure(String username, String password) {
        try {
            service.login(username, password);
            throw new AssertionError("Expected login failure");
        } catch (WebApiException error) {
            return error.getCode();
        }
    }

    @Test
    void loginWithoutHeaderReturnsCodeWhichAccessesConditions() throws Exception {
        String response =
                http.perform(
                                post("/api/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(request("demo_user", "correct")))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.remaining_today").value(42))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String code = json.readTree(response).required("invite_code").asText();
        http.perform(get("/api/conditions").header("X-Invite-Code", code))
                .andExpect(status().isOk());
        verify(invites).remaining(code);
        http.perform(get("/api/auth/login")).andExpect(status().isUnauthorized());
        http.perform(post("/api/auth/login/extra")).andExpect(status().isUnauthorized());
    }

    @Test
    void absentUserWrongPasswordAndInactiveOrMissingCodeHaveSameResponse() throws Exception {
        String missing = loginFailureBody("missing_user", "wrong");
        assertThat(loginFailureBody("demo_user", "wrong")).isEqualTo(missing);
        when(invites.remaining("synthetic-code")).thenThrow(WebApiException.invalidInvite());
        assertThat(loginFailureBody("demo_user", "correct")).isEqualTo(missing);
        assertThat(missing).contains("아이디 또는 비밀번호가 올바르지 않습니다.");
    }

    private String loginFailureBody(String username, String password) throws Exception {
        return http.perform(
                        post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(request(username, password)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_FAILED"))
                .andReturn()
                .getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void fifthFailureLocksForTenMinutesWithoutExtension() {
        for (int i = 0; i < 4; i++) {
            assertThat(failure("demo_user", "wrong")).isEqualTo("LOGIN_FAILED");
        }
        assertThat(failure("demo_user", "wrong")).isEqualTo("LOGIN_RATE_LIMITED");
        when(clock.instant()).thenReturn(start.plus(Duration.ofMinutes(9)));
        assertThat(failure("demo_user", "correct")).isEqualTo("LOGIN_RATE_LIMITED");
        when(clock.instant()).thenReturn(start.plus(Duration.ofMinutes(10)));
        assertThat(service.login("demo_user", "correct")).containsEntry("remaining_today", 42);
        assertThat(failure("demo_user", "wrong")).isEqualTo("LOGIN_FAILED");
    }

    @Test
    void thirtyFirstGlobalFailureLimitsAllAccountsAndExpires() {
        for (int i = 0; i < 30; i++) {
            assertThat(failure("missing_" + i, "wrong")).isEqualTo("LOGIN_FAILED");
        }
        assertThat(failure("missing_30", "wrong")).isEqualTo("LOGIN_RATE_LIMITED");
        assertThat(failure("demo_user", "correct")).isEqualTo("LOGIN_RATE_LIMITED");
        when(clock.instant()).thenReturn(start.plus(Duration.ofMinutes(10)));
        assertThat(service.login("demo_user", "correct")).containsEntry("remaining_today", 42);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "ab",
                "UPPER",
                "bad space",
                "한글아이디",
                "abcdefghijklmnopqrstuvwxyz1234567"
            })
    void validatesUsername(String username) throws Exception {
        http.perform(
                        post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(request(username, "correct")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "null",
                "[]",
                "{\"username\":123,\"password\":\"x\"}",
                "{\"username\":\"demo_user\",\"password\":123}",
                "{\"username\":\"demo_user\",\"password\":null}",
                "{\"username\":\"demo_user\",\"password\":\"\"}",
                "broken"
            })
    void rejectsMalformedAndNonStringRequests(String content) throws Exception {
        http.perform(
                        post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(content))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void acceptsPasswordLengthBoundariesAndRejectsOverTwoHundred() throws Exception {
        loginFailureBody("demo_user", "x");
        loginFailureBody("demo_user", "x".repeat(200));
        http.perform(
                        post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(request("demo_user", "x".repeat(201))))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "does-not-exist",
                "directory",
                "broken",
                "duplicate",
                "invalid-hash",
                "invalid-user",
                "invalid-type",
                "trailing"
            })
    void badFilesDisableOnlyLoginAndLeaveInviteFlowWorking(String kind) throws Exception {
        LoginAccounts loaded = badAccounts(kind);
        assertThat(loaded.enabled()).isFalse();
        assertThat(loaded.find("demo_user")).isNull();
        install(loaded);
        http.perform(
                        post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(request("demo_user", "correct")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOGIN_DISABLED"));
        http.perform(
                        post("/api/invite/verify")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"code\":\"synthetic-code\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remaining_today").value(42));
        http.perform(get("/api/conditions").header("X-Invite-Code", "synthetic-code"))
                .andExpect(status().isOk());
    }

    private LoginAccounts badAccounts(String kind) throws Exception {
        return switch (kind) {
            case "" -> new LoginAccounts("", json);
            case "does-not-exist" ->
                    new LoginAccounts(directory.resolve("absent").toString(), json);
            case "directory" -> new LoginAccounts(directory.toString(), json);
            case "duplicate" -> accounts(List.of(account("demo_user"), account("demo_user")));
            case "invalid-hash" ->
                    accounts(
                            List.of(
                                    Map.of(
                                            "username",
                                            "demo_user",
                                            "password_hash",
                                            "bad",
                                            "invite_code",
                                            "code")));
            case "invalid-user" -> accounts(List.of(account("BAD")));
            case "invalid-type" -> accounts(Map.of("username", "demo_user"));
            default -> {
                Path file = directory.resolve("broken.json");
                Files.writeString(file, kind.equals("trailing") ? "[] {}" : "[broken");
                yield new LoginAccounts(file.toString(), json);
            }
        };
    }

    @Test
    void accountsAreLoadedOnceAndInvalidRequestsDoNotCountAsFailures() throws Exception {
        Files.writeString(directory.resolve("accounts.json"), "broken");
        for (int i = 0; i < 6; i++) {
            http.perform(
                            post("/api/auth/login")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{}"))
                    .andExpect(status().isBadRequest());
        }
        assertThat(service.login("demo_user", "correct"))
                .containsEntry("invite_code", "synthetic-code");
    }
}
