package io.eranalytics.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
final class ErApiClient {
    private final RestClient restClient;
    private final RestClient publicRestClient;
    private final ObjectMapper objectMapper;
    private final RequestRateGate rateGate;
    private final CollectorRepository repository;

    ErApiClient(
            RestClient.Builder builder,
            ObjectMapper objectMapper,
            RequestRateGate rateGate,
            CollectorRepository repository,
            ErApiProperties properties
    ) {
        this.restClient = builder.baseUrl(properties.baseUrl())
                .defaultHeader("x-api-key", properties.key())
                .build();
        this.publicRestClient = RestClient.create();
        this.objectMapper = objectMapper;
        this.rateGate = rateGate;
        this.repository = repository;
    }

    JsonNode get(String endpoint) {
        return get(endpoint, endpoint);
    }

    JsonNode get(String endpoint, String logEndpoint) {
        return execute(() -> restClient.get().uri(endpoint), logEndpoint);
    }

    JsonNode get(String uriTemplate, Map<String, ?> uriVariables, String logEndpoint) {
        return execute(() -> restClient.get().uri(uriTemplate, uriVariables), logEndpoint);
    }

    private JsonNode execute(
            Supplier<RestClient.RequestHeadersSpec<?>> requestSupplier,
            String logEndpoint
    ) {
        rateGate.awaitTurn();
        long started = System.nanoTime();
        try {
            return requestSupplier.get().exchange((request, response) -> {
                HttpStatusCode status = response.getStatusCode();
                byte[] body = response.getBody().readAllBytes();
                if (status.isError()) {
                    repository.logApiCall(logEndpoint, status.value(), elapsedMillis(started));
                    throw new ApiException(status.value(), "ER API returned HTTP " + status.value());
                }
                try {
                    JsonNode payload = objectMapper.readTree(body);
                    int code = payload.path("code").asInt(status.value());
                    repository.logApiCall(logEndpoint, code, elapsedMillis(started));
                    if (code >= 400) {
                        throw new ApiException(code, "ER API payload code " + code);
                    }
                    return payload;
                } catch (IOException exception) {
                    repository.logApiCall(logEndpoint, status.value(), elapsedMillis(started));
                    throw new IllegalStateException("ER API response was not valid JSON", exception);
                }
            });
        } catch (ApiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            repository.logApiCall(logEndpoint, 0, elapsedMillis(started));
            throw exception;
        }
    }

    String getPublicText(String absoluteUrl, String logName) {
        long started = System.nanoTime();
        try {
            return publicRestClient.get().uri(absoluteUrl).exchange((request, response) -> {
                int status = response.getStatusCode().value();
                repository.logApiCall(logName, status, elapsedMillis(started));
                if (response.getStatusCode().isError()) {
                    throw new ApiException(status, "Public data download returned HTTP " + status);
                }
                return new String(response.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            });
        } catch (ApiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            repository.logApiCall(logName, 0, elapsedMillis(started));
            throw exception;
        }
    }

    private long elapsedMillis(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }
}
