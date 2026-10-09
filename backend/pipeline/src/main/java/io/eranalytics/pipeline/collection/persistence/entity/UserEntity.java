package io.eranalytics.pipeline.collection.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "season_id", nullable = false)
    private Integer seasonId;

    @Column(name = "nickname", nullable = false, columnDefinition = "text")
    private String nickname;

    @Column(name = "last_mmr")
    private Integer lastMmr;

    @Column(name = "tier", columnDefinition = "text")
    private String tier;

    @Column(name = "games_seen", nullable = false)
    private Integer gamesSeen = 0;

    @Column(name = "first_seen_at", nullable = false)
    private OffsetDateTime firstSeenAt;

    @Column(name = "last_game_id")
    private Long lastGameId;

    @Column(name = "last_game_at")
    private OffsetDateTime lastGameAt;

    @Column(name = "crawl_status", nullable = false, columnDefinition = "text")
    private String crawlStatus = "NEW";

    @Column(name = "last_crawled_at")
    private OffsetDateTime lastCrawledAt;

    @Column(name = "crawled_newest_game_id")
    private Long crawledNewestGameId;

    @Column(name = "crawl_error", columnDefinition = "text")
    private String crawlError;

    public UserEntity(int seasonId, String nickname, OffsetDateTime firstSeenAt) {
        this.seasonId = seasonId;
        this.nickname = nickname;
        this.firstSeenAt = firstSeenAt;
    }

    public void refresh(long count, LatestUserGame latest, TierEntity boundary) {
        this.gamesSeen = Math.toIntExact(count);
        this.lastMmr = latest.mmr();
        this.lastGameId = latest.gameId();
        this.lastGameAt = latest.startedAt();
        this.tier = boundary == null ? null : boundary.getId().getTier();
    }
}
