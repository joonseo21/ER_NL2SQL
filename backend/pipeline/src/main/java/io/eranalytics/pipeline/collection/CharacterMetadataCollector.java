package io.eranalytics.pipeline.collection;

import com.fasterxml.jackson.databind.JsonNode;
import io.eranalytics.pipeline.collection.metadata.KoreanLocalization;
import io.eranalytics.pipeline.collection.metadata.NameMetadataBatch;
import io.eranalytics.pipeline.collection.metadata.NameMetadataMapper;
import io.eranalytics.pipeline.collection.persistence.NameMetadataStore;
import io.eranalytics.pipeline.config.CollectionProperties;
import io.eranalytics.pipeline.erapi.ErApiClient;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Retains the existing scheduler entry point; all names share one localization download. */
@Component
public class CharacterMetadataCollector {
    private static final Logger log = LoggerFactory.getLogger(CharacterMetadataCollector.class);
    private final ErApiClient api;
    private final CollectorRepository repository;
    private final CollectionRetry retry;
    private final CollectionProperties settings;
    private final Clock clock;
    private final NameMetadataStore names;
    private Instant lastAttempt;

    public CharacterMetadataCollector(
            ErApiClient api,
            CollectorRepository repository,
            CollectionRetry retry,
            CollectionProperties settings,
            Clock clock,
            NameMetadataStore names) {
        this.api = api;
        this.repository = repository;
        this.retry = retry;
        this.settings = settings;
        this.clock = clock;
        this.names = names;
    }

    public void refreshIfDue() {
        Instant now = clock.instant();
        // 갱신 시간 확인
        if (lastAttempt != null && now.isBefore(lastAttempt.plus(settings.metaRefresh()))) return;
        lastAttempt = now;
        try {
            JsonNode characters =
                    retry.call("character metadata", () -> api.get("/v2/data/Character"));
            Map<String, String> korean;
            try {
                JsonNode response =
                        retry.call("Korean localization", () -> api.get("/v1/l10n/Korean"));
                String path = response.path("data").path("l10Path").asText();
                if (path.isBlank()) {
                    throw new IllegalArgumentException("Korean localization path missing");
                }
                String text =
                        retry.call(
                                "Korean localization download",
                                () -> api.getPublicText(path, "l10n/Korean/download"));
                korean = KoreanLocalization.parse(text);
            } catch (CollectionInterruptedException exception) {
                throw exception;
            } catch (RuntimeException error) {
                log.warn("Korean names unavailable ({})", CollectionRetry.reason(error));
                // Keep old Korean names/timestamps while retaining the existing English fallback.
                for (JsonNode row : characters.path("data")) {
                    if (row.path("code").isIntegralNumber() && row.path("code").canConvertToInt()) {
                        repository.upsertCharacter(
                                row.get("code").intValue(), null, row.path("name").asText(null));
                    }
                }
                return;
            }
            Map<String, JsonNode> tables = new LinkedHashMap<>();
            tables.put("Character", characters);
            for (String table : NameMetadataMapper.TABLES) {
                if (!table.equals("Character")) {
                    tables.put(
                            table, retry.call("name metadata", () -> api.get("/v2/data/" + table)));
                }
            }
            NameMetadataBatch batch = NameMetadataMapper.map(tables, korean, now);
            names.save(batch);
            log.info(
                    "Name metadata refreshed; resolved={}, unresolved={}",
                    batch.names().values().stream().mapToInt(java.util.List::size).sum(),
                    batch.unresolved());
        } catch (CollectionInterruptedException exception) {
            throw exception;
        } catch (RuntimeException error) {
            log.warn("Name metadata refresh failed ({})", CollectionRetry.reason(error));
        }
    }
}
