package io.eranalytics.pipeline.collection.user;

import io.eranalytics.pipeline.config.CollectionProperties;
import io.eranalytics.pipeline.config.ErApiProperties;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Ensures initial users, selects the next eligible user and discovers their games. */
@Component
public class UserCollectionCoordinator {
    private final UserCollectionRepository users;
    private final RankedUserSeeder seeder;
    private final UserGameDiscovery discovery;
    private final CollectionProperties settings;
    private final ErApiProperties apiSettings;
    private final Clock clock;

    public UserCollectionCoordinator(
            UserCollectionRepository users,
            RankedUserSeeder seeder,
            UserGameDiscovery discovery,
            CollectionProperties settings,
            ErApiProperties apiSettings,
            Clock clock) {
        this.users = users;
        this.seeder = seeder;
        this.discovery = discovery;
        this.settings = settings;
        this.apiSettings = apiSettings;
        this.clock = clock;
    }

    public boolean collectNext() {
        if (!seeder.ensureUsers()) return false;
        Optional<UserCollectionCandidate> user =
                users.selectNext(
                        apiSettings.seasonId(),
                        settings.minVersionMajor(),
                        settings.minSeedMmr(),
                        OffsetDateTime.now(clock).minus(settings.revisitInterval()));
        if (user.isEmpty()) return false;
        discovery.crawl(user.get());
        return true;
    }
}
