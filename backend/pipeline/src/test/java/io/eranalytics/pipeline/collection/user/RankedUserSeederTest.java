package io.eranalytics.pipeline.collection.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.eranalytics.pipeline.collection.retry.CollectionRetryExecutor;
import io.eranalytics.pipeline.collection.runtime.CollectionSleeper;
import io.eranalytics.pipeline.config.ErApiProperties;
import io.eranalytics.pipeline.erapi.ErApiClient;
import io.eranalytics.pipeline.erapi.dto.RankedUser;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RankedUserSeederTest {
    private final ErApiClient api = mock(ErApiClient.class);
    private final UserCollectionRepository frontier = mock(UserCollectionRepository.class);
    private final RankedUserSeeder bootstrap =
            new RankedUserSeeder(
                    api,
                    frontier,
                    new ErApiProperties("https://example.invalid", "test", 41, 0.5),
                    new CollectionRetryExecutor(mock(CollectionSleeper.class)));

    @Test
    void existingSeasonUsersSkipRankingAndNicknameApi() {
        when(frontier.hasUsers(41)).thenReturn(true);
        assertThat(bootstrap.ensureUsers()).isTrue();
        verifyNoInteractions(api);
        verify(frontier, never()).seedIfEmpty(anyInt(), anyList());
    }

    @Test
    void emptySeasonSeedsEntireReturnedRankingWithoutNicknameLookups() {
        List<SeedUser> seeds =
                IntStream.range(0, 1000)
                        .mapToObj(i -> new SeedUser("synthetic_" + i, 8000))
                        .toList();
        when(frontier.hasUsers(41)).thenReturn(false, true);
        when(api.topUsers(41))
                .thenReturn(
                        seeds.stream()
                                .map(seed -> new RankedUser(seed.nickname(), seed.mmr()))
                                .toList());
        assertThat(bootstrap.ensureUsers()).isTrue();
        verify(frontier).seedIfEmpty(41, seeds);
        verify(api).topUsers(41);
        verifyNoMoreInteractions(api);
    }
}
