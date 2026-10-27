package io.eranalytics.pipeline.persistence.user;

import io.eranalytics.pipeline.collection.user.SeedUser;
import io.eranalytics.pipeline.collection.user.UserCollectionCandidate;
import io.eranalytics.pipeline.collection.user.UserCollectionRepository;
import io.eranalytics.pipeline.persistence.TransactionAdvisoryLock;
import io.eranalytics.pipeline.persistence.entity.TierEntity;
import io.eranalytics.pipeline.persistence.entity.UserEntity;
import io.eranalytics.pipeline.persistence.repository.TierRepository;
import io.eranalytics.pipeline.persistence.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class PostgresUserCollectionRepository implements UserCollectionRepository {
    private static final List<String> TIERS = List.of("플래티넘", "다이아몬드", "메테오라이트", "미스릴 이상");
    private static final Duration TIER_SAMPLE_REFRESH = Duration.ofHours(1);
    private final EntityManager entityManager;
    private final UserRepository users;
    private final TierRepository tiers;
    private final TransactionAdvisoryLock lock;
    private final Clock clock;
    private final TierSampleCache tierSamples;

    public PostgresUserCollectionRepository(
            EntityManager entityManager,
            UserRepository users,
            TierRepository tiers,
            TransactionAdvisoryLock lock,
            Clock clock) {
        this.entityManager = entityManager;
        this.users = users;
        this.tiers = tiers;
        this.lock = lock;
        this.clock = clock;
        this.tierSamples = new TierSampleCache(TIER_SAMPLE_REFRESH, clock);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasUsers(int seasonId) {
        return users.existsBySeasonId(seasonId);
    }

    @Override
    @Transactional
    public void seedIfEmpty(int seasonId, List<SeedUser> seeds) {
        lock.acquire("seed:" + seasonId);
        if (users.existsBySeasonId(seasonId)) return;
        List<TierEntity> boundaries = tiers.findByIdSeasonIdOrderByMinMmrDesc(seasonId);
        if (boundaries.isEmpty()) {
            throw new IllegalStateException("No tier boundaries for configured season");
        }
        // Use the same sorted natural-key locks as game persistence.
        List<SeedUser> ordered =
                seeds.stream().sorted(Comparator.comparing(SeedUser::nickname)).toList();
        ordered.stream()
                .map(SeedUser::nickname)
                .distinct()
                .forEach(name -> lock.acquire("user:" + seasonId + ":" + name));
        for (SeedUser seed : ordered) {
            if (users.findBySeasonIdAndNickname(seasonId, seed.nickname()).isPresent()) continue;
            UserEntity user = new UserEntity(seasonId, seed.nickname(), OffsetDateTime.now(clock));
            TierEntity boundary =
                    boundaries.stream()
                            .filter(t -> seed.mmr() >= t.getMinMmr())
                            .findFirst()
                            .orElse(null);
            user.seed(seed.mmr(), boundary);
            users.save(user);
        }
    }

    /**
     * 티어별로 고르게 게임을 뽑을 다음 사람을 선택해 반환.
     *
     * <p>티어별 표본 수는 1시간 간격으로만 다시 센다. 센 값을 갱신하다 실패해도 이 메서드가 하나의 트랜잭션에 묶여 있으면 이후 조회가 모두 막히므로, 메서드 전체를
     * 트랜잭션 없이 실행한다.
     */
    @Override
    public Optional<UserCollectionCandidate> selectNext(
            int seasonId, int minVersionMajor, int minMmr, OffsetDateTime revisitBefore) {
        Map<String, Long> samples =
                tierSamples.get(
                        seasonId,
                        minVersionMajor,
                        () -> countLatestPatchTiers(seasonId, minVersionMajor));
        // 참가자 수별 티어 내림차수 정렬 리스트
        List<String> order = new ArrayList<>(TIERS);
        order.sort(Comparator.comparingLong(tier -> samples.getOrDefault(tier, 0L)));

        for (String tier : order) {
            Optional<UserCollectionCandidate> candidate =
                    pick(seasonId, tier, minMmr, revisitBefore, true);
            if (candidate.isEmpty()) candidate = pick(seasonId, tier, minMmr, revisitBefore, false);
            if (candidate.isPresent()) return candidate;
        }
        return Optional.empty();
    }

    /** 강제로 다음 선택 때 티어별 표본 수를 다시 센다. */
    public void refreshTierSamples() {
        tierSamples.invalidate();
    }

    /**
     * 최신 패치 참가자의 티어별 개수. 경기 번호를 먼저 뽑고 그 번호의 참가자만 세어야 participants 본체 대신 V6 커버링 인덱스만 읽는다. 캐시를 거치지 않고
     * 매번 DB를 읽으므로 선택 경로에서 직접 호출하지 않는다.
     */
    public Map<String, Long> countLatestPatchTiers(int seasonId, int minVersionMajor) {
        // Aggregation/picking are PostgreSQL-specific queries; entity writes remain JPA.
        List<?> rows =
                entityManager
                        .createNativeQuery(
                                """
                SELECT p.tier, count(*) FROM participants p
                WHERE p.game_id = ANY (ARRAY(
                  SELECT g.game_id FROM games g
                  WHERE g.season_id = :season AND g.matching_mode = 3 AND g.matching_team_mode = 3
                    AND g.version_major = (
                      SELECT max(version_major) FROM games
                      WHERE season_id = :season AND matching_mode = 3 AND matching_team_mode = 3
                        AND version_major >= :major AND version_minor IS NOT NULL)
                    AND g.version_minor = (
                      SELECT max(version_minor) FROM games
                      WHERE season_id = :season AND matching_mode = 3 AND matching_team_mode = 3
                        AND version_minor IS NOT NULL
                        AND version_major = (
                          SELECT max(version_major) FROM games
                          WHERE season_id = :season AND matching_mode = 3 AND matching_team_mode = 3
                            AND version_major >= :major AND version_minor IS NOT NULL))))
                GROUP BY p.tier
                """)
                        .setParameter("season", seasonId)
                        .setParameter("major", minVersionMajor)
                        .getResultList();

        // <티어별, participants(참가자 god) 개수> 맵 생성
        Map<String, Long> samples = new HashMap<>();
        for (Object row : rows) {
            Object[] values = (Object[]) row;
            // A NULL tier is not one of the sampled tiers, and the cache copies the map.
            if (values[0] == null) {
                continue;
            }
            samples.put((String) values[0], ((Number) values[1]).longValue());
        }
        return samples;
    }

    /** fresh true -> 새로운 유저 찾아서 랜덤으로 fresh false -> 완료된 사람 중 오래된 순서로 */
    private Optional<UserCollectionCandidate> pick(
            int seasonId, String tier, int minMmr, OffsetDateTime revisitBefore, boolean fresh) {
        // Only fixed SQL fragments are interpolated; every value is bound.
        String eligibility =
                fresh
                        ? "crawl_status = 'NEW'"
                        : "crawl_status = 'DONE' AND last_crawled_at < :before";
        String order = fresh ? "random()" : "last_crawled_at, user_id";
        Query query =
                entityManager
                        .createNativeQuery(
                                """
                SELECT * FROM users WHERE season_id = :season AND tier = :tier AND last_mmr >= :mmr
                  AND %s ORDER BY %s LIMIT 1
                """
                                        .formatted(eligibility, order),
                                UserEntity.class)
                        .setParameter("season", seasonId)
                        .setParameter("tier", tier)
                        .setParameter("mmr", minMmr);
        if (!fresh) query.setParameter("before", revisitBefore);
        List<?> results = query.getResultList();
        if (results.isEmpty()) return Optional.empty();
        UserEntity user = (UserEntity) results.getFirst();
        return Optional.of(
                new UserCollectionCandidate(
                        user.getUserId(),
                        user.getSeasonId(),
                        user.getNickname(),
                        user.getCrawledNewestGameId()));
    }

    @Override
    @Transactional
    public void complete(UserCollectionCandidate user, Long newestGameId, boolean fullyScanned) {
        lock.acquire("user:" + user.seasonId() + ":" + user.nickname());
        users.findById(user.userId())
                .orElseThrow()
                .completeCrawl(OffsetDateTime.now(clock), newestGameId, fullyScanned);
    }

    @Override
    @Transactional
    public void fail(UserCollectionCandidate user, boolean notFound, String reason) {
        lock.acquire("user:" + user.seasonId() + ":" + user.nickname());
        users.findById(user.userId())
                .orElseThrow()
                .failCrawl(OffsetDateTime.now(clock), notFound, reason);
    }
}
