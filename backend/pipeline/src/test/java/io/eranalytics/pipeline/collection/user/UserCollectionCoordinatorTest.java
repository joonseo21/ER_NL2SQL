package io.eranalytics.pipeline.collection.user;

import static io.eranalytics.pipeline.collection.CollectionTestSettings.settings;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.eranalytics.pipeline.config.ErApiProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class UserCollectionCoordinatorTest {
    private final UserCollectionRepository users = mock(UserCollectionRepository.class);
    private final RankedUserSeeder seeder = mock(RankedUserSeeder.class);
    private final UserGameDiscovery discovery = mock(UserGameDiscovery.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-09T00:00:00Z"), ZoneOffset.UTC);
    private final OffsetDateTime before = OffsetDateTime.now(clock).minusHours(4);
    private final UserCollectionCoordinator coordinator =
            new UserCollectionCoordinator(
                    users,
                    seeder,
                    discovery,
                    settings(30, false),
                    new ErApiProperties("https://example.invalid", "synthetic-key", 41, 0.5),
                    clock);

    @Test
    void failedSeedingDoesNotSelectOrCrawlUsers() {
        assertThat(coordinator.collectNext()).isFalse();
        verifyNoInteractions(users, discovery);
    }

    @Test
    void noEligibleUserDoesNotRequestGames() {
        when(seeder.ensureUsers()).thenReturn(true);
        when(users.selectNext(41, 4, 3600, before)).thenReturn(Optional.empty());
        assertThat(coordinator.collectNext()).isFalse();
        verifyNoInteractions(discovery);
    }

    @Test
    void selectsWithConfiguredScopeAndRevisitTimeThenDiscoversExactlyThatUser() {
        UserCollectionCandidate user = new UserCollectionCandidate(1, 41, "synthetic_user", null);
        when(seeder.ensureUsers()).thenReturn(true);
        when(users.selectNext(41, 4, 3600, before)).thenReturn(Optional.of(user));
        assertThat(coordinator.collectNext()).isTrue();
        InOrder order = inOrder(seeder, users, discovery);
        order.verify(seeder).ensureUsers();
        order.verify(users).selectNext(41, 4, 3600, before);
        order.verify(discovery).crawl(user);
    }
}
