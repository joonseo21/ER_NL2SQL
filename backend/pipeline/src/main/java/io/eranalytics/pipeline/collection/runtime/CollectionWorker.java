package io.eranalytics.pipeline.collection.runtime;

import io.eranalytics.pipeline.collection.game.GameCollectionProcessor;
import io.eranalytics.pipeline.collection.metadata.NameMetadataRefresher;
import io.eranalytics.pipeline.collection.user.UserCollectionCoordinator;
import io.eranalytics.pipeline.config.CollectionProperties;
import io.eranalytics.pipeline.config.ErApiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Single-worker loop: refresh names, drain games, discover user games, then wait or exit. */
@Component
@Order(10)
@ConditionalOnProperty(name = "er.collection.enabled", havingValue = "true")
final class CollectionWorker implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(CollectionWorker.class);
    private final GameCollectionProcessor games;
    private final UserCollectionCoordinator users;
    private final NameMetadataRefresher metadata;
    private final CollectionSleeper sleeper;
    private final ErApiProperties apiSettings;
    private final CollectionProperties settings;

    CollectionWorker(
            GameCollectionProcessor games,
            UserCollectionCoordinator users,
            NameMetadataRefresher metadata,
            CollectionSleeper sleeper,
            ErApiProperties apiSettings,
            CollectionProperties settings) {
        this.games = games;
        this.users = users;
        this.metadata = metadata;
        this.sleeper = sleeper;
        this.apiSettings = apiSettings;
        this.settings = settings;
    }

    @Override
    public void run(ApplicationArguments args) {
        settings.validate();
        if (apiSettings.seasonId() <= 0
                || apiSettings.key() == null
                || apiSettings.key().isBlank()) {
            throw new IllegalStateException(
                    "ER_SEASON_ID and ER_API_KEY must be set when collection is enabled");
        }
        try {
            while (!Thread.currentThread().isInterrupted()) {
                metadata.refreshIfDue();
                if (games.processNext()) continue;
                if (users.collectNext()) continue;
                if (!settings.continuous()) {
                    log.info("No eligible games/users; collection finished");
                    return;
                }
                log.info("No eligible games/users; waiting {}", settings.idleSleep());
                sleeper.sleep(settings.idleSleep());
            }
        } catch (CollectionInterruptedException exception) {
            log.info("Collection stopped after interruption");
        }
    }
}
