package io.eranalytics.pipeline.collection.persistence.entity;

import io.eranalytics.pipeline.erapi.dto.CombatDto;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CombatFields {
    @Column(name = "damage_to_player_basic")
    private Integer damageToPlayerBasic;

    @Column(name = "damage_to_player_skill")
    private Integer damageToPlayerSkill;

    @Column(name = "damage_to_player_item_skill")
    private Integer damageToPlayerItemSkill;

    @Column(name = "damage_to_player_direct")
    private Integer damageToPlayerDirect;

    @Column(name = "damage_to_monster")
    private Integer damageToMonster;

    @Column(name = "damage_from_player")
    private Integer damageFromPlayer;

    @Column(name = "damage_from_player_basic")
    private Integer damageFromPlayerBasic;

    @Column(name = "damage_from_player_skill")
    private Integer damageFromPlayerSkill;

    @Column(name = "damage_from_player_item_skill")
    private Integer damageFromPlayerItemSkill;

    @Column(name = "damage_from_player_direct")
    private Integer damageFromPlayerDirect;

    @Column(name = "damage_from_monster")
    private Integer damageFromMonster;

    @Column(name = "damage_offseted_by_shield_player")
    private Integer damageOffsetedByShieldPlayer;

    @Column(name = "heal_amount")
    private Integer healAmount;

    @Column(name = "team_recover")
    private Integer teamRecover;

    @Column(name = "protect_absorb")
    private Integer protectAbsorb;

    @Column(name = "team_down")
    private Integer teamDown;

    @Column(name = "team_elimination")
    private Integer teamElimination;

    @Column(name = "terminate_count")
    private Integer terminateCount;

    @Column(name = "clutch_count")
    private Integer clutchCount;

    @Column(name = "kills_phase_one")
    private Integer killsPhaseOne;

    @Column(name = "kills_phase_two")
    private Integer killsPhaseTwo;

    @Column(name = "kills_phase_three")
    private Integer killsPhaseThree;

    @Column(name = "deaths_phase_one")
    private Integer deathsPhaseOne;

    @Column(name = "deaths_phase_two")
    private Integer deathsPhaseTwo;

    @Column(name = "deaths_phase_three")
    private Integer deathsPhaseThree;

    public CombatFields(CombatDto data) {
        this.damageToPlayerBasic = data.damageToPlayerBasic();
        this.damageToPlayerSkill = data.damageToPlayerSkill();
        this.damageToPlayerItemSkill = data.damageToPlayerItemSkill();
        this.damageToPlayerDirect = data.damageToPlayerDirect();
        this.damageToMonster = data.damageToMonster();
        this.damageFromPlayer = data.damageFromPlayer();
        this.damageFromPlayerBasic = data.damageFromPlayerBasic();
        this.damageFromPlayerSkill = data.damageFromPlayerSkill();
        this.damageFromPlayerItemSkill = data.damageFromPlayerItemSkill();
        this.damageFromPlayerDirect = data.damageFromPlayerDirect();
        this.damageFromMonster = data.damageFromMonster();
        this.damageOffsetedByShieldPlayer = data.damageOffsetedByShieldPlayer();
        this.healAmount = data.healAmount();
        this.teamRecover = data.teamRecover();
        this.protectAbsorb = data.protectAbsorb();
        this.teamDown = data.teamDown();
        this.teamElimination = data.teamElimination();
        this.terminateCount = data.terminateCount();
        this.clutchCount = data.clutchCount();
        this.killsPhaseOne = data.killsPhaseOne();
        this.killsPhaseTwo = data.killsPhaseTwo();
        this.killsPhaseThree = data.killsPhaseThree();
        this.deathsPhaseOne = data.deathsPhaseOne();
        this.deathsPhaseTwo = data.deathsPhaseTwo();
        this.deathsPhaseThree = data.deathsPhaseThree();
    }
}
