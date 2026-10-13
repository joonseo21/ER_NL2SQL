package io.eranalytics.pipeline.webapi.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.eranalytics.pipeline.webapi.service.QuestionFailure;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Sends only question/conditions and the configured token; no HTTP retry or redirects. */
public class HttpAgentClient implements AgentClient {
    private final RestClient client;
    private final ObjectMapper json;

    public HttpAgentClient(
            ObjectMapper json,
            String baseUrl,
            String token,
            Duration connectTimeout,
            Duration readTimeout) {
        if (token == null || !token.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "AGENT_INTERNAL_TOKEN must be configured as 64 lowercase hex characters");
        }
        if (connectTimeout.isNegative()
                || connectTimeout.isZero()
                || readTimeout.isNegative()
                || readTimeout.isZero()) {
            throw new IllegalArgumentException("Agent timeouts must be positive");
        }
        HttpClient transport =
                HttpClient.newBuilder()
                        .connectTimeout(connectTimeout)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .version(HttpClient.Version.HTTP_1_1)
                        .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(transport);
        factory.setReadTimeout(readTimeout);
        this.client =
                RestClient.builder()
                        .baseUrl(address(baseUrl).toString())
                        .requestFactory(factory)
                        .defaultHeader("X-Agent-Token", token)
                        .build();
        this.json = json;
    }

    @Override
    public ObjectNode query(String question, JsonNode conditions) {
        ObjectNode request = json.createObjectNode().put("question", question);
        if (conditions != null) request.set("conditions", conditions);
        try {
            return client.post()
                    .uri("/v1/query")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(request)
                    .exchange(
                            (outbound, response) -> {
                                if (response.getStatusCode().value() != 200) {
                                    throw failure(response.getStatusCode().value()).exception();
                                }
                                try {
                                    return AgentResponseValidator.validate(
                                            json.readTree(response.getBody()));
                                } catch (JsonProcessingException malformed) {
                                    throw QuestionFailure.AGENT_INVALID_RESPONSE.exception();
                                }
                            });
        } catch (ResourceAccessException error) {
            throw (timedOut(error)
                            ? QuestionFailure.AGENT_TIMEOUT
                            : QuestionFailure.AGENT_CONNECTION_FAILED)
                    .exception();
        } catch (RestClientException error) {
            throw QuestionFailure.AGENT_CONNECTION_FAILED.exception();
        }
    }

    private QuestionFailure failure(int status) {
        return switch (status) {
            case 401 -> QuestionFailure.AGENT_AUTH_FAILED;
            case 422 -> QuestionFailure.AGENT_REQUEST_REJECTED;
            case 500 -> QuestionFailure.AGENT_INTERNAL_ERROR;
            case 503 -> QuestionFailure.AGENT_BUSY;
            default -> QuestionFailure.AGENT_HTTP_ERROR;
        };
    }

    private boolean timedOut(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private URI address(String value) {
        try {
            URI address = new URI(value);
            if (!("http".equals(address.getScheme()) || "https".equals(address.getScheme()))
                    || address.getHost() == null
                    || hasPrivateOrExtraParts(address)
                    || address.getPort() == 0
                    || address.getPort() > 65535) {
                throw new IllegalArgumentException(
                        "Agent base URL must be an HTTP server address without credentials or a path");
            }
            return address;
        } catch (URISyntaxException malformed) {
            throw new IllegalArgumentException("Agent base URL is invalid");
        }
    }

    private boolean hasPrivateOrExtraParts(URI address) {
        return address.getUserInfo() != null
                || address.getQuery() != null
                || address.getFragment() != null
                || (!address.getPath().isEmpty() && !"/".equals(address.getPath()));
    }
}
