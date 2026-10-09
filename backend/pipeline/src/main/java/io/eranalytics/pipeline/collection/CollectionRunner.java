package io.eranalytics.pipeline.collection;

import io.eranalytics.pipeline.config.CollectionProperties;
import io.eranalytics.pipeline.config.ErApiProperties;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Single-worker scheduling only; fetching, retry and persistence live in their own components. */
@Component
@Order(10)
@ConditionalOnProperty(name = "er.collection.enabled", havingValue = "true")
final class CollectionRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(CollectionRunner.class);
    private final CollectorRepository games;
    private final UserFrontierRepository frontier;
    private final UserSeedBootstrap bootstrap;
    private final UserGameCrawler crawler;
    private final GameJobProcessor processor;
    private final CharacterMetadataCollector metadata;
    private final CollectionSleeper sleeper;
    private final ErApiProperties apiSettings;
    private final CollectionProperties settings;
    private final Clock clock;

    CollectionRunner(CollectorRepository games, UserFrontierRepository frontier, UserSeedBootstrap bootstrap,
                     UserGameCrawler crawler, GameJobProcessor processor, CharacterMetadataCollector metadata,
                     CollectionSleeper sleeper, ErApiProperties apiSettings, CollectionProperties settings, Clock clock) {
        this.games = games;
        this.frontier = frontier;
        this.bootstrap = bootstrap;
        this.crawler = crawler;
        this.processor = processor;
        this.metadata = metadata;
        this.sleeper = sleeper;
        this.apiSettings = apiSettings;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        settings.validate();
        if (apiSettings.seasonId() <= 0 || apiSettings.key() == null || apiSettings.key().isBlank()) {
            throw new IllegalStateException("ER_SEASON_ID and ER_API_KEY must be set when collection is enabled");
        }
        try {
            while (!Thread.currentThread().isInterrupted()) {
                metadata.refreshIfDue();
                var job = games.claimNextGame();
                if (job.isPresent()) {
                    processor.process(job.get());
                    continue;
                }
                if (bootstrap.ensureUsers()) {
                    var user = frontier.selectNext(apiSettings.seasonId(), settings.minVersionMajor(),
                            settings.minSeedMmr(), OffsetDateTime.now(clock).minus(settings.revisitInterval()));
                    if (user.isPresent()) {
                        crawler.crawl(user.get());
                        continue;
                    }
                }
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
