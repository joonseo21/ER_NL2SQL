package io.eranalytics.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(10)
@ConditionalOnProperty(name = "er.collection.enabled", havingValue = "true")
final class CollectionRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(CollectionRunner.class);

    private final ErApiClient api;
    private final CollectorRepository repository;
    private final ErApiProperties properties;
    private final CollectionProperties collectionProperties;

    CollectionRunner(ErApiClient api, CollectorRepository repository, ErApiProperties properties,
                     CollectionProperties collectionProperties) {
        this.api = api;
        this.repository = repository;
        this.properties = properties;
        this.collectionProperties = collectionProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        validateConfiguration();
        do {
            collectOnce();
            if (collectionProperties.continuous()) {
                Duration interval = collectionProperties.interval();
                log.info("Next collection cycle starts in {}", interval);
                sleep(interval);
            }
        } while (collectionProperties.continuous());
    }

    private void collectOnce() {
        collectCharacterMetadata();
        String ranksEndpoint = "/v1/rank/top/%d/3".formatted(properties.seasonId());
        JsonNode ranks = withBootstrapRetry(ranksEndpoint, () -> api.get(ranksEndpoint));
        seedRankers(ranks.path("topRanks"));

        repository.claimNext().ifPresentOrElse(this::processUntilEmpty,
                () -> log.info("Collection queue is empty"));
    }

    private void processUntilEmpty(QueueJob first) {
        QueueJob current = first;
        while (current != null) {
            process(current);
            current = repository.claimNext().orElse(null);
        }
        log.info("Collection queue drained");
    }

    private void process(QueueJob job) {
        try {
            if ("USER".equals(job.jobType())) {
                collectUser(job.targetKey());
            } else if ("GAME".equals(job.jobType())) {
                collectGame(job.targetKey());
            } else {
                throw new IllegalArgumentException("Unknown job type: " + job.jobType());
            }
            repository.markDone(job.id());
        } catch (RuntimeException exception) {
            boolean retryable = !(exception instanceof ApiException apiException)
                    || apiException.retryable();
            repository.markFailure(job, exception.getMessage(), retryable);
            log.warn("Collection job {}:{} failed on attempt {}: {}",
                    job.jobType(), job.targetKey(), job.attempts(), exception.getMessage());
            if (retryable && job.attempts() < 5) {
                backoff(job.attempts());
            }
        }
    }

    private void collectUser(String uid) {
        JsonNode response = api.get(
                "/v1/user/games/uid/" + uid,
                "/v1/user/games/uid/{uid}");
        for (JsonNode game : response.path("userGames")) {
            if (game.path("matchingMode").asInt() == 3
                    && game.path("matchingTeamMode").asInt() == 3
                    && game.hasNonNull("gameId")) {
                repository.enqueue("GAME", game.get("gameId").asText());
            }
        }
    }

    private void collectGame(String gameId) {
        JsonNode response = api.get(
                "/v1/games/" + gameId,
                "/v1/games/{gameId}");
        List<JsonNode> participants = new ArrayList<>();
        response.path("userGames").forEach(participants::add);
        repository.upsertGame(participants);
    }

    private void seedRankers(JsonNode topRanks) {
        int processed = 0;
        for (JsonNode ranker : topRanks) {
            if (processed++ >= properties.topRankerLimit()) {
                break;
            }
            String nickname = ranker.path("nickname").asText();
            if (nickname.isBlank()) {
                continue;
            }
            try {
                JsonNode response = withBootstrapRetry(
                        "/v1/user/nickname",
                        () -> api.get(
                                "/v1/user/nickname?query={nickname}",
                                Map.of("nickname", nickname),
                                "/v1/user/nickname"));
                String userId = response.path("user").path("userId").asText();
                if (userId.isBlank()) {
                    log.warn("Nickname lookup succeeded but userId was absent for rank {}",
                            ranker.path("rank").asInt());
                    continue;
                }
                repository.upsertRankerAndQueue(
                        userId, nickname, nullableInt(ranker, "rank"), nullableInt(ranker, "mmr"),
                        properties.seasonId());
            } catch (ApiException exception) {
                log.warn("Could not resolve rank {} to userId: HTTP {}",
                        ranker.path("rank").asInt(), exception.statusCode());
            }
        }
    }

    private void collectCharacterMetadata() {
        try {
            JsonNode characterResponse = withBootstrapRetry(
                    "/v2/data/Character", () -> api.get("/v2/data/Character"));
            Map<Integer, String> koreanNames;
            try {
                koreanNames = loadKoreanCharacterNames();
            } catch (RuntimeException exception) {
                log.warn("Korean character names were unavailable; keeping English names: {}",
                        exception.getMessage());
                koreanNames = Map.of();
            }
            for (JsonNode character : characterResponse.path("data")) {
                if (!character.path("code").canConvertToInt()) {
                    continue;
                }
                int code = character.get("code").intValue();
                String englishName = character.path("name").asText(null);
                repository.upsertCharacter(code, koreanNames.get(code), englishName);
            }
        } catch (RuntimeException exception) {
            log.warn("Character metadata collection failed; matches will still be collected: {}",
                    exception.getMessage());
        }
    }

    private Map<Integer, String> loadKoreanCharacterNames() {
        JsonNode response = withBootstrapRetry(
                "/v1/l10n/Korean", () -> api.get("/v1/l10n/Korean"));
        String path = response.path("data").path("l10Path").asText();
        if (path.isBlank()) {
            throw new IllegalStateException("Korean l10n response did not contain l10Path");
        }
        String text = withBootstrapRetry(
                "l10n/Korean/download", () -> api.getPublicText(path, "l10n/Korean/download"));
        Map<Integer, String> names = new HashMap<>();
        for (String line : text.lines().toList()) {
            int separator = line.indexOf('┃');
            if (separator < 0 || !line.startsWith("Character/Name/")) {
                continue;
            }
            try {
                int code = Integer.parseInt(line.substring("Character/Name/".length(), separator));
                names.put(code, line.substring(separator + 1));
            } catch (NumberFormatException ignored) {
                // Ignore unrelated or malformed localization keys.
            }
        }
        return names;
    }

    private <T> T withBootstrapRetry(String operation, Supplier<T> call) {
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                return call.get();
            } catch (ApiException exception) {
                if (!exception.retryable() || attempt == 5) {
                    throw exception;
                }
                long delaySeconds = backoffSeconds(attempt);
                log.warn("ER API operation {} returned HTTP {}. Retrying attempt {}/5 in {}s",
                        operation, exception.statusCode(), attempt + 1, delaySeconds);
                sleep(delaySeconds);
            }
        }
        throw new IllegalStateException("Unreachable retry state");
    }

    private void backoff(int attempts) {
        sleep(backoffSeconds(attempts));
    }

    private long backoffSeconds(int attempts) {
        return Math.min(60, 1L << Math.min(attempts - 1, 6));
    }

    private void sleep(long seconds) {
        sleep(Duration.ofSeconds(seconds));
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during retry backoff", exception);
        }
    }

    private Integer nullableInt(JsonNode node, String field) {
        return node.hasNonNull(field) && node.get(field).canConvertToInt()
                ? node.get(field).intValue()
                : null;
    }

    private void validateConfiguration() {
        if (properties.seasonId() <= 0) {
            throw new IllegalStateException("ER_SEASON_ID must be set when collection is enabled");
        }
        if (properties.key() == null || properties.key().isBlank()) {
            throw new IllegalStateException("ER_API_KEY must be set when collection is enabled");
        }
        if (collectionProperties.continuous()
                && (collectionProperties.interval() == null
                || collectionProperties.interval().isNegative()
                || collectionProperties.interval().isZero())) {
            throw new IllegalStateException("ER_COLLECTION_INTERVAL must be positive");
        }
    }
}
