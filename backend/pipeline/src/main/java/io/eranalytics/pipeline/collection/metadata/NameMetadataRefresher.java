package io.eranalytics.pipeline.collection.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import io.eranalytics.pipeline.collection.retry.CollectionRetryExecutor;
import io.eranalytics.pipeline.collection.runtime.CollectionInterruptedException;
import io.eranalytics.pipeline.config.CollectionProperties;
import io.eranalytics.pipeline.erapi.ErApiClient;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Refreshes all official name categories; one localization download is shared by the snapshot. */
@Component
public class NameMetadataRefresher {
    private static final Logger log = LoggerFactory.getLogger(NameMetadataRefresher.class);
    private final ErApiClient api;
    private final CollectionRetryExecutor retry;
    private final CollectionProperties settings;
    private final Clock clock;
    private final NameMetadataStore names;
    private Instant lastAttempt;

    public NameMetadataRefresher(
            ErApiClient api,
            CollectionRetryExecutor retry,
            CollectionProperties settings,
            Clock clock,
            NameMetadataStore names) {
        this.api = api;
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
                korean = loadKoreanNames();
            } catch (CollectionInterruptedException exception) {
                throw exception;
            } catch (RuntimeException error) {
                log.warn("Korean names unavailable ({})", CollectionRetryExecutor.reason(error));
                // Keep old Korean names/timestamps while retaining the existing English fallback.
                retainEnglishNames(characters);
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
            log.warn("Name metadata refresh failed ({})", CollectionRetryExecutor.reason(error));
        }
    }

    private Map<String, String> loadKoreanNames() {
        JsonNode response = retry.call("Korean localization", () -> api.get("/v1/l10n/Korean"));
        String path = response.path("data").path("l10Path").asText();
        if (path.isBlank()) {
            throw new IllegalArgumentException("Korean localization path missing");
        }
        String text =
                retry.call(
                        "Korean localization download",
                        () -> api.getPublicText(path, "l10n/Korean/download"));
        return KoreanLocalization.parse(text);
    }

    private void retainEnglishNames(JsonNode characters) {
        for (JsonNode row : characters.path("data")) {
            if (row.path("code").isIntegralNumber() && row.path("code").canConvertToInt()) {
                names.saveCharacterNames(
                        row.get("code").intValue(), null, row.path("name").asText(null));
            }
        }
    }
}
