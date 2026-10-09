package io.eranalytics.pipeline.collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.eranalytics.pipeline.collection.model.SeedUser;
import io.eranalytics.pipeline.config.ErApiProperties;
import io.eranalytics.pipeline.erapi.ErApiClient;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class UserSeedBootstrapTest {
    private final ErApiClient api = mock(ErApiClient.class);
    private final UserFrontierRepository frontier = mock(UserFrontierRepository.class);
    private final UserSeedBootstrap bootstrap = new UserSeedBootstrap(api, frontier,
            new ErApiProperties("https://example.invalid", "test", 41, 0.5),
            new CollectionRetry(mock(CollectionSleeper.class)));

    @Test
    void existingSeasonUsersSkipRankingAndNicknameApi() {
        when(frontier.hasUsers(41)).thenReturn(true);
        assertThat(bootstrap.ensureUsers()).isTrue();
        verifyNoInteractions(api);
        verify(frontier, never()).seedIfEmpty(anyInt(), anyList());
    }

    @Test
    void emptySeasonSeedsEntireReturnedRankingWithoutNicknameLookups() {
        var seeds = IntStream.range(0, 1000).mapToObj(i -> new SeedUser("synthetic_" + i, 8000)).toList();
        when(frontier.hasUsers(41)).thenReturn(false, true);
        when(api.topUsers(41)).thenReturn(seeds);
        assertThat(bootstrap.ensureUsers()).isTrue();
        verify(frontier).seedIfEmpty(41, seeds);
        verify(api).topUsers(41);
        verifyNoMoreInteractions(api);
    }
}
