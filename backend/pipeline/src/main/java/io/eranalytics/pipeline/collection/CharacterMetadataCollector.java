package io.eranalytics.pipeline.collection;

import io.eranalytics.pipeline.config.CollectionProperties;
import io.eranalytics.pipeline.erapi.ErApiClient;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Current character/l10n loader. Other name-table loaders can be added separately in stage 6. */
@Component
public class CharacterMetadataCollector {
    private static final Logger log = LoggerFactory.getLogger(CharacterMetadataCollector.class);
    private final ErApiClient api;
    private final CollectorRepository repository;
    private final CollectionRetry retry;
    private final CollectionProperties settings;
    private final Clock clock;
    private Instant lastAttempt;

    public CharacterMetadataCollector(ErApiClient api, CollectorRepository repository, CollectionRetry retry,
                                      CollectionProperties settings, Clock clock) {
        this.api = api;
        this.repository = repository;
        this.retry = retry;
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * 실험체·한국어 이름을 갱신하고 24시간 갱신 시점을 관리.
     */
    public void refreshIfDue() {
        Instant now = clock.instant();
        if (lastAttempt != null && now.isBefore(lastAttempt.plus(settings.metaRefresh()))) return;
        // Includes failed refreshes so an outage does not trigger metadata on every game.
        lastAttempt = now;
        try {
            var characters = retry.call("character metadata", () -> api.get("/v2/data/Character"));
            Map<Integer, String> korean;
            try {
                korean = koreanNames();
            } catch (CollectionInterruptedException exception) {
                throw exception;
            } catch (RuntimeException error) {
                log.warn("Korean character names unavailable ({})", CollectionRetry.reason(error));
                korean = Map.of();
            }
            for (var character : characters.path("data")) {
                if (!character.path("code").isIntegralNumber() || !character.path("code").canConvertToInt()) continue;
                int code = character.get("code").intValue();
                repository.upsertCharacter(code, korean.get(code), character.path("name").asText(null));
            }
        } catch (CollectionInterruptedException exception) {
            throw exception;
        } catch (RuntimeException error) {
            log.warn("Character metadata refresh failed ({})", CollectionRetry.reason(error));
        }
    }

    private Map<Integer, String> koreanNames() {
        var response = retry.call("Korean localization", () -> api.get("/v1/l10n/Korean"));
        String path = response.path("data").path("l10Path").asText();
        if (path.isBlank()) throw new IllegalArgumentException("Korean localization path missing");
        String text = retry.call("Korean localization download", () -> api.getPublicText(path, "l10n/Korean/download"));
        Map<Integer, String> names = new HashMap<>();
        for (String line : text.lines().toList()) {
            int separator = line.indexOf('┃');
            if (separator < 0 || !line.startsWith("Character/Name/")) continue;
            try {
                int code = Integer.parseInt(line.substring("Character/Name/".length(), separator));
                names.put(code, line.substring(separator + 1));
            } catch (NumberFormatException ignored) {
                // Malformed/unrelated localization keys are not character names.
            }
        }
        return names;
    }
}
