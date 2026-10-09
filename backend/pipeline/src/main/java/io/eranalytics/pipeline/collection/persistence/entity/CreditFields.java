package io.eranalytics.pipeline.collection.persistence.entity;

import io.eranalytics.pipeline.erapi.dto.CreditDto;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreditFields {
    @Column(name = "credit_revival_count")
    private Integer creditRevivalCount;

    @Column(name = "credit_revived_others_count")
    private Integer creditRevivedOthersCount;

    @Column(name = "total_gain_vf_credit")
    private Integer totalGainVfCredit;

    @Column(name = "total_use_vf_credit")
    private Integer totalUseVfCredit;

    @Column(name = "kill_player_gain_vf_credit")
    private Integer killPlayerGainVfCredit;

    @Column(name = "kill_chicken_gain_vf_credit")
    private Integer killChickenGainVfCredit;

    @Column(name = "kill_boar_gain_vf_credit")
    private Integer killBoarGainVfCredit;

    @Column(name = "kill_wild_dog_gain_vf_credit")
    private Integer killWildDogGainVfCredit;

    @Column(name = "kill_wolf_gain_vf_credit")
    private Integer killWolfGainVfCredit;

    @Column(name = "kill_bear_gain_vf_credit")
    private Integer killBearGainVfCredit;

    @Column(name = "kill_bat_gain_vf_credit")
    private Integer killBatGainVfCredit;

    @Column(name = "kill_raven_gain_vf_credit")
    private Integer killRavenGainVfCredit;

    @Column(name = "kill_wickline_gain_vf_credit")
    private Integer killWicklineGainVfCredit;

    @Column(name = "kill_alpha_gain_vf_credit")
    private Integer killAlphaGainVfCredit;

    @Column(name = "kill_omega_gain_vf_credit")
    private Integer killOmegaGainVfCredit;

    @Column(name = "kill_gamma_gain_vf_credit")
    private Integer killGammaGainVfCredit;

    @Column(name = "kill_drone_gain_vf_credit")
    private Integer killDroneGainVfCredit;

    @Column(name = "kill_item_bounty_gain_vf_credit")
    private Integer killItemBountyGainVfCredit;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "total_vf_credits", columnDefinition = "integer[]")
    private Integer[] totalVfCredits;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "used_vf_credits", columnDefinition = "integer[]")
    private Integer[] usedVfCredits;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "total_tk_per_min", columnDefinition = "integer[]")
    private Integer[] totalTkPerMin;

    public CreditFields(CreditDto data) {
        this.creditRevivalCount = data.creditRevivalCount();
        this.creditRevivedOthersCount = data.creditRevivedOthersCount();
        this.totalGainVfCredit = data.totalGainVfCredit();
        this.totalUseVfCredit = data.totalUseVfCredit();
        this.killPlayerGainVfCredit = data.killPlayerGainVfCredit();
        this.killChickenGainVfCredit = data.killChickenGainVfCredit();
        this.killBoarGainVfCredit = data.killBoarGainVfCredit();
        this.killWildDogGainVfCredit = data.killWildDogGainVfCredit();
        this.killWolfGainVfCredit = data.killWolfGainVfCredit();
        this.killBearGainVfCredit = data.killBearGainVfCredit();
        this.killBatGainVfCredit = data.killBatGainVfCredit();
        this.killRavenGainVfCredit = data.killRavenGainVfCredit();
        this.killWicklineGainVfCredit = data.killWicklineGainVfCredit();
        this.killAlphaGainVfCredit = data.killAlphaGainVfCredit();
        this.killOmegaGainVfCredit = data.killOmegaGainVfCredit();
        this.killGammaGainVfCredit = data.killGammaGainVfCredit();
        this.killDroneGainVfCredit = data.killDroneGainVfCredit();
        this.killItemBountyGainVfCredit = data.killItemBountyGainVfCredit();
        this.totalVfCredits = data.totalVfCredits() == null ? null : data.totalVfCredits().toArray(Integer[]::new);
        this.usedVfCredits = data.usedVfCredits() == null ? null : data.usedVfCredits().toArray(Integer[]::new);
        this.totalTkPerMin = data.totalTkPerMin() == null ? null : data.totalTkPerMin().toArray(Integer[]::new);
    }
}
