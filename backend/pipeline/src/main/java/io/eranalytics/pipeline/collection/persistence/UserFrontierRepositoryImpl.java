package io.eranalytics.pipeline.collection.persistence;

import io.eranalytics.pipeline.collection.UserFrontierRepository;
import io.eranalytics.pipeline.collection.model.CrawlUser;
import io.eranalytics.pipeline.collection.model.SeedUser;
import io.eranalytics.pipeline.collection.persistence.entity.UserEntity;
import io.eranalytics.pipeline.collection.persistence.repository.TierRepository;
import io.eranalytics.pipeline.collection.persistence.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
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
public class UserFrontierRepositoryImpl implements UserFrontierRepository {
    private static final List<String> TIERS = List.of("플래티넘", "다이아몬드", "메테오라이트", "미스릴 이상");
    private final EntityManager entityManager;
    private final UserRepository users;
    private final TierRepository tiers;
    private final GameWriteLock lock;
    private final Clock clock;

    public UserFrontierRepositoryImpl(EntityManager entityManager, UserRepository users,
                                     TierRepository tiers, GameWriteLock lock, Clock clock) {
        this.entityManager = entityManager;
        this.users = users;
        this.tiers = tiers;
        this.lock = lock;
        this.clock = clock;
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
        var boundaries = tiers.findByIdSeasonIdOrderByMinMmrDesc(seasonId);
        if (boundaries.isEmpty()) throw new IllegalStateException("No tier boundaries for configured season");
        // Use the same sorted natural-key locks as game persistence.
        var ordered = seeds.stream().sorted(Comparator.comparing(SeedUser::nickname)).toList();
        ordered.stream().map(SeedUser::nickname).distinct()
                .forEach(name -> lock.acquire("user:" + seasonId + ":" + name));
        for (SeedUser seed : ordered) {
            if (users.findBySeasonIdAndNickname(seasonId, seed.nickname()).isPresent()) continue;
            UserEntity user = new UserEntity(seasonId, seed.nickname(), OffsetDateTime.now(clock));
            var boundary = boundaries.stream().filter(t -> seed.mmr() >= t.getMinMmr()).findFirst().orElse(null);
            user.seed(seed.mmr(), boundary);
            users.save(user);
        }
    }

    /**
     * 티어별로 고르게 게임을 뽑을 다음 사람을 선택해 반환.
     * @param seasonId
     * @param minVersionMajor
     * @param minMmr
     * @param revisitBefore
     * @return
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<CrawlUser> selectNext(int seasonId, int minVersionMajor, int minMmr,
                                         OffsetDateTime revisitBefore) {
        // Aggregation/picking are PostgreSQL-specific queries; entity writes remain JPA.
        var rows = entityManager.createNativeQuery("""
                WITH latest_patch AS (
                  SELECT version_major, version_minor FROM games
                  WHERE season_id = :season AND matching_mode = 3 AND matching_team_mode = 3
                    AND version_major >= :major AND version_minor IS NOT NULL
                  ORDER BY version_major DESC, version_minor DESC LIMIT 1
                )
                SELECT p.tier, count(*) FROM participants p
                JOIN games g ON g.game_id = p.game_id
                JOIN latest_patch v ON p.version_major = v.version_major AND p.version_minor = v.version_minor
                WHERE g.season_id = :season AND g.matching_mode = 3 AND g.matching_team_mode = 3
                GROUP BY p.tier
                """).setParameter("season", seasonId).setParameter("major", minVersionMajor).getResultList();

        // <티어별, participants(참가자 god) 개수> 맵 생성
        Map<String, Long> samples = new HashMap<>();
        for (Object row : rows) {
            Object[] values = (Object[]) row;
            samples.put((String) values[0], ((Number) values[1]).longValue());
        }
        // 참가자 수별 티어 내림차수 정렬 리스트
        List<String> order = new ArrayList<>(TIERS);
        order.sort(Comparator.comparingLong(tier -> samples.getOrDefault(tier, 0L)));

        for (String tier : order) {
            var candidate = pick(seasonId, tier, minMmr, revisitBefore, true);
            if (candidate.isEmpty()) candidate = pick(seasonId, tier, minMmr, revisitBefore, false);
            if (candidate.isPresent()) return candidate;
        }
        return Optional.empty();
    }

    /**
     * fresh true   -> 새로운 유저 찾아서 랜덤으로
     * fresh false  -> 완료된 사람 중 오래된 순서로
     */
    private Optional<CrawlUser> pick(int seasonId, String tier, int minMmr,
                                     OffsetDateTime revisitBefore, boolean fresh) {
        // Only fixed SQL fragments are interpolated; every value is bound.
        String eligibility = fresh ? "crawl_status = 'NEW'" :
                "crawl_status = 'DONE' AND last_crawled_at < :before";
        String order = fresh ? "random()" : "last_crawled_at, user_id";
        var query = entityManager.createNativeQuery("""
                SELECT * FROM users WHERE season_id = :season AND tier = :tier AND last_mmr >= :mmr
                  AND %s ORDER BY %s LIMIT 1
                """.formatted(eligibility, order), UserEntity.class)
                .setParameter("season", seasonId).setParameter("tier", tier).setParameter("mmr", minMmr);
        if (!fresh) query.setParameter("before", revisitBefore);
        List<?> results = query.getResultList();
        if (results.isEmpty()) return Optional.empty();
        UserEntity user = (UserEntity) results.getFirst();
        return Optional.of(new CrawlUser(user.getUserId(), user.getSeasonId(), user.getNickname(),
                user.getCrawledNewestGameId()));
    }

    @Override
    @Transactional
    public void complete(CrawlUser user, Long newestGameId, boolean fullyScanned) {
        lock.acquire("user:" + user.seasonId() + ":" + user.nickname());
        users.findById(user.userId()).orElseThrow().completeCrawl(
                OffsetDateTime.now(clock), newestGameId, fullyScanned);
    }

    @Override
    @Transactional
    public void fail(CrawlUser user, boolean notFound, String reason) {
        lock.acquire("user:" + user.seasonId() + ":" + user.nickname());
        users.findById(user.userId()).orElseThrow().failCrawl(OffsetDateTime.now(clock), notFound, reason);
    }
}
