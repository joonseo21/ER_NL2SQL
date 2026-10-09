package io.eranalytics.pipeline.collection.persistence.entity;

import io.eranalytics.pipeline.collection.model.GameData;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "games")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GameEntity extends AssignedIdEntity<Long> {
    @Id
    @Column(name = "game_id")
    private Long gameId;

    @Column(name = "season_id")
    private Integer seasonId;

    @Column(name = "matching_mode")
    private Integer matchingMode;

    @Column(name = "matching_team_mode")
    private Integer matchingTeamMode;

    @Column(name = "version_season")
    private Integer versionSeason;

    @Column(name = "version_major")
    private Integer versionMajor;

    @Column(name = "version_minor")
    private Integer versionMinor;

    @Column(name = "match_size")
    private Integer matchSize;

    @Column(name = "mmr_avg")
    private Integer mmrAvg;

    @Column(name = "main_weather")
    private Integer mainWeather;

    @Column(name = "sub_weather")
    private Integer subWeather;

    @Column(name = "start_dtm")
    private OffsetDateTime startDtm;

    @Column(name = "server_name", columnDefinition = "text")
    private String serverName;

    @Column(name = "team_count")
    private Integer teamCount;

    @Column(name = "fetched_at", nullable = false)
    private OffsetDateTime fetchedAt;

    public GameEntity(GameData data, OffsetDateTime fetchedAt) {
        apply(data, fetchedAt);
    }

    @Override
    public Long getId() {
        return gameId;
    }

    public void apply(GameData data, OffsetDateTime fetchedAt) {
        var fields = data.fields();
        this.gameId = fields.gameId();
        this.seasonId = fields.seasonId();
        this.matchingMode = fields.matchingMode();
        this.matchingTeamMode = fields.matchingTeamMode();
        this.versionSeason = fields.versionSeason();
        this.versionMajor = fields.versionMajor();
        this.versionMinor = fields.versionMinor();
        this.matchSize = fields.matchSize();
        this.mmrAvg = fields.mmrAvg();
        this.mainWeather = fields.mainWeather();
        this.subWeather = fields.subWeather();
        this.startDtm = fields.startDtm();
        this.serverName = fields.serverName();
        this.teamCount = data.teamCount();
        this.fetchedAt = fetchedAt;
    }
}
