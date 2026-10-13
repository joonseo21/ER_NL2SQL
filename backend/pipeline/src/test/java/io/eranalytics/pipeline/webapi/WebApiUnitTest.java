package io.eranalytics.pipeline.webapi;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.eranalytics.pipeline.webapi.client.MockAgentClient;
import io.eranalytics.pipeline.webapi.config.InviteCodeFilter;
import io.eranalytics.pipeline.webapi.controller.WebApiController;
import io.eranalytics.pipeline.webapi.controller.WebApiErrorHandler;
import io.eranalytics.pipeline.webapi.service.ConditionsService;
import io.eranalytics.pipeline.webapi.service.FeedbackService;
import io.eranalytics.pipeline.webapi.service.InviteService;
import io.eranalytics.pipeline.webapi.service.QuestionService;
import io.eranalytics.pipeline.webapi.service.RequestConditions;
import io.eranalytics.pipeline.webapi.service.WebApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WebApiUnitTest {
    private final ObjectMapper json = new ObjectMapper();
    private final InviteService invites = mock(InviteService.class);
    private final QuestionService questions = mock(QuestionService.class);
    private final FeedbackService feedback = mock(FeedbackService.class);
    private MockMvc http;

    @BeforeEach
    void setup() {
        ConditionsService conditions = mock(ConditionsService.class);
        when(invites.requireActive("valid")).thenReturn("valid");
        when(conditions.list()).thenReturn(json.createObjectNode());
        http =
                MockMvcBuilders.standaloneSetup(
                                new WebApiController(invites, conditions, questions, feedback))
                        .setControllerAdvice(new WebApiErrorHandler())
                        .addFilters(new InviteCodeFilter(invites, json))
                        .build();
    }

    @Test
    void rejectsMissingAndDuplicateHeadersBeforeControllers() throws Exception {
        http.perform(get("/api/conditions"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVITE_INVALID"));
        http.perform(get("/api/conditions").header("X-Invite-Code", "valid", "valid"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(questions, feedback);
    }

    @Test
    void rejectsInactiveCodeAndAcceptsValidCode() throws Exception {
        when(invites.requireActive("inactive")).thenThrow(WebApiException.invalidInvite());
        http.perform(get("/api/conditions").header("X-Invite-Code", "inactive"))
                .andExpect(status().isUnauthorized());
        http.perform(get("/api/conditions").header("X-Invite-Code", "valid"))
                .andExpect(status().isOk());
    }

    @Test
    void verifyDoesNotRequireHeaderOrConsumeQuestion() throws Exception {
        when(invites.remaining("valid")).thenReturn(50);
        http.perform(
                        post("/api/invite/verify")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"code\":\"valid\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remaining_today").value(50));
        verifyNoInteractions(questions);
    }

    @Test
    void questionReturnsReceiptWithResultLocation() throws Exception {
        ObjectNode receipt =
                json.createObjectNode()
                        .put("request_id", "receipt-test")
                        .put("status", "accepted")
                        .put("remaining_today", 49);
        when(questions.ask(eq("valid"), any())).thenReturn(receipt);
        http.perform(
                        post("/api/questions")
                                .header("X-Invite-Code", "valid")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"question\":\"질문\"}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/questions/receipt-test"))
                .andExpect(jsonPath("$.status").value("accepted"));
    }

    @Test
    void rejectsBlankQuestionsAndOversizedFeedback() throws Exception {
        http.perform(
                        post("/api/questions")
                                .header("X-Invite-Code", "valid")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"question\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        http.perform(
                        post("/api/questions/log/feedback")
                                .header("X-Invite-Code", "valid")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                java.util.Map.of(
                                                        "rating",
                                                        "like",
                                                        "comment",
                                                        "가".repeat(501)))))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(questions, feedback);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "null",
                "[]",
                "{\"patch\":{\"mode\":\"exact\",\"versions\":[]}}",
                "{\"patch\":{\"mode\":\"latest\",\"versions\":[{\"major\":5,\"minor\":0}]}}",
                "{\"tier_division\":1}",
                "{\"character_code\":-1}",
                "{\"unknown\":1}"
            })
    void rejectsInvalidConditions(String input) throws Exception {
        assertThatThrownBy(() -> RequestConditions.validate(json.readTree(input)))
                .isInstanceOf(WebApiException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "answer",
                "answer-without-summary",
                "answer-empty-result",
                "notice",
                "clarification"
            })
    void fixturesPreserveResponseAndGenerateUniqueLogIds(String scenario) throws Exception {
        MockAgentClient agent = new MockAgentClient(json, scenario);
        ObjectNode first = agent.query("질문", json.createObjectNode());
        ObjectNode second = agent.query("질문", json.createObjectNode());
        assertThat(first.path("log_id").asText()).isNotEqualTo(second.path("log_id").asText());
        assertThat(first.path("question").asText()).isEqualTo("질문");
        assertThat(first.path("rows").size()).isLessThanOrEqualTo(200);
        assertThat(first.path("warnings").toString()).contains("가짜 에이전트");
        if (scenario.equals("answer-empty-result")) {
            assertThat(first.path("columns")).isNotEmpty();
            assertThat(first.path("rows")).isEmpty();
        }
        if (scenario.equals("answer-without-summary")) {
            assertThat(first.path("summary").isNull()).isTrue();
            assertThat(first.path("rows")).isNotEmpty();
        }
    }
}
