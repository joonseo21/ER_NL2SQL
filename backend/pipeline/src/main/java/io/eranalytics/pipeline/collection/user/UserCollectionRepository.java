package io.eranalytics.pipeline.collection.user;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface UserCollectionRepository {
    boolean hasUsers(int seasonId);

    void seedIfEmpty(int seasonId, List<SeedUser> seeds);

    /** 티어별로 고르게 게임을 뽑을 다음 사람을 선택해 반환. */
    Optional<UserCollectionCandidate> selectNext(
            int seasonId, int minVersionMajor, int minMmr, OffsetDateTime revisitBefore);

    void complete(UserCollectionCandidate user, Long newestGameId, boolean fullyScanned);

    void fail(UserCollectionCandidate user, boolean notFound, String reason);
}
