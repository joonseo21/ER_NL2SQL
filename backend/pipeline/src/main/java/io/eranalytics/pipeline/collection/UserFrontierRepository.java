package io.eranalytics.pipeline.collection;

import io.eranalytics.pipeline.collection.model.CrawlUser;
import io.eranalytics.pipeline.collection.model.SeedUser;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface UserFrontierRepository {
    boolean hasUsers(int seasonId);
    void seedIfEmpty(int seasonId, List<SeedUser> seeds);

    /**
     * 티어별로 고르게 게임을 뽑을 다음 사람을 선택해 반환.
      */
    Optional<CrawlUser> selectNext(int seasonId, int minVersionMajor, int minMmr,
                                  OffsetDateTime revisitBefore);
    void complete(CrawlUser user, Long newestGameId, boolean fullyScanned);
    void fail(CrawlUser user, boolean notFound, String reason);
}
