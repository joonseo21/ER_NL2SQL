package io.eranalytics.pipeline.erapi;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.eranalytics.pipeline.config.ErApiProperties;
import io.eranalytics.pipeline.erapi.dto.RankedUser;
import io.eranalytics.pipeline.erapi.dto.UserGamesPage;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ErApiClient {
    private final RestClient restClient;
    private final RestClient publicRestClient;
    private final ObjectMapper objectMapper;
    private final ObjectReader userPageReader;
    private final ErApiRateLimiter rateGate;
    private final ApiCallLog callLog;

    public ErApiClient(
            RestClient.Builder builder,
            ObjectMapper objectMapper,
            ErApiRateLimiter rateGate,
            ApiCallLog callLog,
            ErApiProperties properties) {
        this.restClient =
                builder.baseUrl(properties.baseUrl())
                        .defaultHeader("x-api-key", properties.key())
                        .build();
        this.publicRestClient = RestClient.create();
        this.objectMapper = objectMapper;
        this.userPageReader =
                JsonMapper.builder()
                        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                        .build()
                        .readerFor(UserGamesPage.class);
        this.rateGate = rateGate;
        this.callLog = callLog;
    }

    public JsonNode get(String endpoint) {
        return get(endpoint, endpoint);
    }

    public JsonNode get(String endpoint, String logEndpoint) {
        return execute(() -> restClient.get().uri(endpoint), logEndpoint);
    }

    public JsonNode get(String uriTemplate, Map<String, ?> uriVariables, String logEndpoint) {
        return execute(() -> restClient.get().uri(uriTemplate, uriVariables), logEndpoint);
    }

    public String resolveUser(String nickname) {
        JsonNode response =
                get(
                        "/v1/user/nickname?query={nickname}",
                        Map.of("nickname", nickname),
                        "/v1/user/nickname");
        String uid = response.path("user").path("userId").asText();
        if (uid.isBlank()) {
            throw new IllegalArgumentException("Nickname response is missing userId");
        }
        return uid;
    }

    public UserGamesPage userGames(String uid, Long next) {
        String path = "/v1/user/games/uid/{uid}";
        Map<String, ?> variables =
                next == null ? Map.of("uid", uid) : Map.of("uid", uid, "next", next);
        JsonNode response = get(next == null ? path : path + "?next={next}", variables, path);
        try {
            return userPageReader.readValue(response);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Invalid user games response");
        }
    }

    public List<RankedUser> topUsers(int seasonId) {
        JsonNode ranks =
                get(
                                "/v1/rank/top/{season}/3",
                                Map.of("season", seasonId),
                                "/v1/rank/top/{season}/3")
                        .path("topRanks");
        if (!ranks.isArray() || ranks.isEmpty()) {
            throw new IllegalArgumentException("Invalid top ranks response");
        }
        List<RankedUser> seeds = new ArrayList<>();
        for (JsonNode rank : ranks) {
            String nickname = rank.path("nickname").asText();
            if (!rank.path("nickname").isTextual()
                    || nickname.isBlank()
                    || !rank.path("mmr").isIntegralNumber()
                    || !rank.path("mmr").canConvertToInt()) {
                throw new IllegalArgumentException("Invalid rank seed fields");
            }
            seeds.add(new RankedUser(nickname, rank.get("mmr").intValue()));
        }
        return List.copyOf(seeds);
    }

    public List<JsonNode> gameParticipants(String gameId) {
        JsonNode results =
                get("/v1/games/{gameId}", Map.of("gameId", gameId), "/v1/games/{gameId}")
                        .path("userGames");
        if (!results.isArray() || results.isEmpty()) {
            throw new IllegalArgumentException("Invalid game response");
        }
        List<JsonNode> participants = new ArrayList<>();
        results.forEach(participants::add);
        return List.copyOf(participants);
    }

    public JsonNode recommendRoute(int routeId) {
        return get(
                "/v1/weaponRoutes/recommend/{routeId}",
                Map.of("routeId", routeId),
                "/v1/weaponRoutes/recommend/{routeId}");
    }

    private JsonNode execute(
            Supplier<RestClient.RequestHeadersSpec<?>> requestSupplier, String logEndpoint) {
        rateGate.awaitTurn();
        long started = System.nanoTime();
        try {
            return requestSupplier
                    .get()
                    .exchange(
                            (request, response) -> {
                                HttpStatusCode status = response.getStatusCode();
                                byte[] body = response.getBody().readAllBytes();
                                if (status.isError()) {
                                    callLog.logApiCall(
                                            logEndpoint, status.value(), elapsedMillis(started));
                                    throw new ErApiException(
                                            status.value(),
                                            "ER API returned HTTP " + status.value());
                                }
                                try {
                                    JsonNode payload = objectMapper.readTree(body);
                                    int code = payload.path("code").asInt(status.value());
                                    callLog.logApiCall(logEndpoint, code, elapsedMillis(started));
                                    if (code >= 400) {
                                        throw new ErApiException(
                                                code, "ER API payload code " + code);
                                    }
                                    return payload;
                                } catch (IOException exception) {
                                    throw new IllegalStateException(
                                            "ER API response was not valid JSON", exception);
                                }
                            });
        } catch (ErApiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            callLog.logApiCall(logEndpoint, 0, elapsedMillis(started));
            throw exception;
        }
    }

    public String getPublicText(String absoluteUrl, String logName) {
        rateGate.awaitTurn();
        long started = System.nanoTime();
        try {
            return publicRestClient
                    .get()
                    .uri(absoluteUrl)
                    .exchange(
                            (request, response) -> {
                                int status = response.getStatusCode().value();
                                callLog.logApiCall(logName, status, elapsedMillis(started));
                                if (response.getStatusCode().isError()) {
                                    throw new ErApiException(
                                            status, "Public data download returned HTTP " + status);
                                }
                                return new String(
                                        response.getBody().readAllBytes(),
                                        java.nio.charset.StandardCharsets.UTF_8);
                            });
        } catch (ErApiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            callLog.logApiCall(logName, 0, elapsedMillis(started));
            throw exception;
        }
    }

    private long elapsedMillis(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }
}
