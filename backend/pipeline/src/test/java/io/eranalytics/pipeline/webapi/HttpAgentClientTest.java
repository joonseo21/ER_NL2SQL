package io.eranalytics.pipeline.webapi;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.eranalytics.pipeline.webapi.client.HttpAgentClient;
import io.eranalytics.pipeline.webapi.client.MockAgentClient;
import io.eranalytics.pipeline.webapi.config.AgentClientConfiguration;
import io.eranalytics.pipeline.webapi.service.QuestionFailure;
import io.eranalytics.pipeline.webapi.service.WebApiException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class HttpAgentClientTest {
    private static final String TOKEN = "a".repeat(64);
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<JsonNode> body = new AtomicReference<>();
    private final AtomicReference<String> receivedToken = new AtomicReference<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private HttpServer server;
    private ExecutorService threads;
    private String response;
    private int status;
    private boolean delayed;

    @BeforeEach
    void start() throws Exception {
        response =
                new MockAgentClient(json, "answer").query("질문", json.createObjectNode()).toString();
        status = 200;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        threads = Executors.newCachedThreadPool();
        server.setExecutor(threads);
        server.createContext(
                "/",
                exchange -> {
                    calls.incrementAndGet();
                    assertThat(exchange.getRequestURI().getPath()).isEqualTo("/v1/query");
                    assertThat(exchange.getRequestMethod()).isEqualTo("POST");
                    receivedToken.set(exchange.getRequestHeaders().getFirst("X-Agent-Token"));
                    assertThat(exchange.getRequestHeaders().getFirst("X-Invite-Code")).isNull();
                    body.set(json.readTree(exchange.getRequestBody()));
                    if (delayed) {
                        try {
                            release.await(2, TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    if (status == 302) exchange.getResponseHeaders().add("Location", "/redirected");
                    byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
    }

    @AfterEach
    void stop() {
        release.countDown();
        server.stop(0);
        threads.shutdownNow();
    }

    private HttpAgentClient client(Duration readTimeout) {
        return new HttpAgentClient(
                json,
                "http://127.0.0.1:" + server.getAddress().getPort(),
                TOKEN,
                Duration.ofSeconds(1),
                readTimeout);
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
    void postsOnlyExpectedFieldsAndPreservesAllResponseKinds(String scenario) throws Exception {
        ObjectNode expected =
                new MockAgentClient(json, scenario).query("질문", json.createObjectNode());
        response = expected.toString();
        JsonNode conditions = json.readTree("{\"character_code\":17}");
        assertThat(client(Duration.ofSeconds(2)).query("질문", conditions)).isEqualTo(expected);
        assertThat(receivedToken.get()).isEqualTo(TOKEN);
        assertThat(body.get().size()).isEqualTo(2);
        assertThat(body.get().path("conditions")).isEqualTo(conditions);
        assertThat(calls).hasValue(1);
    }

    @ParameterizedTest
    @CsvSource({
        "401,AGENT_AUTH_FAILED",
        "422,AGENT_REQUEST_REJECTED",
        "500,AGENT_INTERNAL_ERROR",
        "503,AGENT_BUSY",
        "302,AGENT_HTTP_ERROR",
        "404,AGENT_HTTP_ERROR"
    })
    void mapsHttpErrorsWithoutReturningBodyOrRetrying(int httpStatus, QuestionFailure failure) {
        status = httpStatus;
        response = "{\"detail\":\"PRIVATE_ERROR_BODY\"}";
        assertThatThrownBy(() -> client(Duration.ofSeconds(2)).query("질문", null))
                .isInstanceOfSatisfying(
                        WebApiException.class,
                        error -> {
                            assertThat(error.getCode()).isEqualTo(failure.name());
                            assertThat(error.getMessage()).isEqualTo(failure.getMessage());
                            assertThat(error.getMessage())
                                    .doesNotContain("PRIVATE_ERROR_BODY", TOKEN);
                            assertThat(error.getCause()).isNull();
                        });
        assertThat(calls).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not JSON", "null", "[]", "{\"kind\":\"answer\"}"})
    void rejectsMalformedResponses(String invalid) {
        response = invalid;
        assertThatThrownBy(() -> client(Duration.ofSeconds(2)).query("질문", null))
                .isInstanceOfSatisfying(
                        WebApiException.class,
                        error -> assertThat(error.getCode()).isEqualTo("AGENT_INVALID_RESPONSE"));
        assertThat(calls).hasValue(1);
    }

    @Test
    void timesOutWithoutRetrying() {
        delayed = true;
        assertThatThrownBy(() -> client(Duration.ofMillis(200)).query("질문", null))
                .isInstanceOfSatisfying(
                        WebApiException.class,
                        error -> assertThat(error.getCode()).isEqualTo("AGENT_TIMEOUT"));
        assertThat(calls).hasValue(1);
    }

    @Test
    void mapsConnectionRefusal() {
        HttpAgentClient client = client(Duration.ofSeconds(1));
        server.stop(0);
        assertThatThrownBy(() -> client.query("질문", null))
                .isInstanceOfSatisfying(
                        WebApiException.class,
                        error -> assertThat(error.getCode()).isEqualTo("AGENT_CONNECTION_FAILED"));
    }

    @Test
    void missingTokenPreventsHttpStartupAndMockNeedsNoToken() {
        ApplicationContextRunner context =
                new ApplicationContextRunner()
                        .withUserConfiguration(AgentClientConfiguration.class)
                        .withBean(ObjectMapper.class, ObjectMapper::new)
                        .withPropertyValues("er.web-api.enabled=true", "AGENT_INTERNAL_TOKEN=");
        context.run(application -> assertThat(application).hasFailed());
        context.withPropertyValues("er.web-api.agent.mode=mock")
                .run(
                        application -> {
                            assertThat(application).hasNotFailed();
                            assertThat(
                                            application.getBean(
                                                    io.eranalytics.pipeline.webapi.client
                                                            .AgentClient.class))
                                    .isInstanceOf(MockAgentClient.class);
                        });
    }

    @Test
    void rejectsOversizedResult() throws Exception {
        ObjectNode large = (ObjectNode) json.readTree(response);
        com.fasterxml.jackson.databind.node.ArrayNode rows = large.putArray("rows");
        for (int index = 0; index < 201; index++) rows.addObject();
        response = large.toString();
        assertThatThrownBy(() -> client(Duration.ofSeconds(2)).query("질문", null))
                .isInstanceOfSatisfying(
                        WebApiException.class,
                        error -> assertThat(error.getCode()).isEqualTo("AGENT_INVALID_RESPONSE"));
    }
}
