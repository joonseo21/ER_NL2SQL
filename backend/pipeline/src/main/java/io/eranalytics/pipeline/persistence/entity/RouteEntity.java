package io.eranalytics.pipeline.persistence.entity;

import com.fasterxml.jackson.databind.JsonNode;
import io.eranalytics.pipeline.collection.route.RouteSnapshot;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "routes")
public class RouteEntity {
    @EmbeddedId private RouteId id;

    @Column(name = "fetch_status", columnDefinition = "text")
    private String fetchStatus;

    @Column(name = "usage_count")
    private Long usageCount;

    @Column(name = "response_code")
    private Integer responseCode;

    @Column(name = "fetched_at")
    private Instant fetchedAt;

    @Column(name = "v2_season_id")
    private Integer v2SeasonId;

    @Column(name = "character_code")
    private Integer characterCode;

    @Column(name = "weapon_type")
    private Integer weaponType;

    @Column(name = "tactical_skill_group_code")
    private Integer tacticalSkillGroupCode;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "weapon_codes", columnDefinition = "integer[]")
    private Integer[] weaponCodes;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "trait_codes", columnDefinition = "integer[]")
    private Integer[] traitCodes;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "remote_transfer_item_codes", columnDefinition = "integer[]")
    private Integer[] remoteTransferItemCodes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "late_game_item_codes", columnDefinition = "jsonb")
    private JsonNode lateGameItemCodes;

    @Column(name = "v2_win_rate")
    private Double v2WinRate;

    @Column(name = "v2_like")
    private Long v2Like;

    @Column(name = "v2_accumulate_win_rate")
    private Double v2AccumulateWinRate;

    @Column(name = "v2_accumulate_like")
    private Long v2AccumulateLike;

    @Column(name = "route_version")
    private Integer routeVersion;

    @Column(name = "update_dtm")
    private Instant updateDtm;

    @Column(name = "version_season")
    private Integer versionSeason;

    @Column(name = "version_major")
    private Integer versionMajor;

    @Column(name = "version_minor")
    private Integer versionMinor;

    @Column(name = "team_mode")
    private Integer teamMode;

    private Boolean share;

    public void apply(RouteSnapshot row, Instant now) {
        fetchStatus = row.version().isEmpty() ? "INVALID_VERSION" : "SUCCESS";
        fetchedAt = now;
        responseCode = row.responseCode();
        v2SeasonId = row.v2SeasonId();
        characterCode = row.characterCode();
        weaponType = row.weaponType();
        tacticalSkillGroupCode = row.tacticalSkillGroupCode();
        weaponCodes = row.weaponCodes().toArray(Integer[]::new);
        traitCodes = row.traitCodes().toArray(Integer[]::new);
        remoteTransferItemCodes = row.remoteTransferItemCodes().toArray(Integer[]::new);
        lateGameItemCodes = row.lateGameItemCodes().deepCopy();
        v2WinRate = row.v2WinRate();
        v2Like = row.v2Like();
        v2AccumulateWinRate = row.v2AccumulateWinRate();
        v2AccumulateLike = row.v2AccumulateLike();
        routeVersion = row.routeVersion();
        updateDtm = row.updateDtm();
        versionSeason = row.version().isEmpty() ? null : row.version().get(0);
        versionMajor = row.version().isEmpty() ? null : row.version().get(1);
        versionMinor = row.version().isEmpty() ? null : row.version().get(2);
        teamMode = row.teamMode();
        share = row.share();
    }
}
