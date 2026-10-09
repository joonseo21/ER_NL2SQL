package io.eranalytics.pipeline.collection.persistence.entity;

import io.eranalytics.pipeline.erapi.dto.CharacterStatsDto;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CharacterStatsFields {
    @Column(name = "character_level")
    private Integer characterLevel;

    @Column(name = "skin_code")
    private Integer skinCode;

    @Column(name = "account_level")
    private Integer accountLevel;

    @Column(name = "max_hp")
    private Integer maxHp;

    @Column(name = "attack_power")
    private Integer attackPower;

    @Column(name = "defense")
    private Integer defense;

    @Column(name = "skill_amp")
    private Integer skillAmp;

    @Column(name = "adaptive_force")
    private Integer adaptiveForce;

    @Column(name = "cc_time_to_player")
    private Double ccTimeToPlayer;

    @Column(name = "attack_speed")
    private Double attackSpeed;

    @Column(name = "move_speed")
    private Double moveSpeed;

    @Column(name = "cool_down_reduction")
    private Double coolDownReduction;

    @Column(name = "critical_strike_chance")
    private Double criticalStrikeChance;

    @Column(name = "life_steal")
    private Double lifeSteal;

    @Column(name = "attack_range")
    private Double attackRange;

    @Column(name = "sight_range")
    private Double sightRange;

    public CharacterStatsFields(CharacterStatsDto data) {
        this.characterLevel = data.characterLevel();
        this.skinCode = data.skinCode();
        this.accountLevel = data.accountLevel();
        this.maxHp = data.maxHp();
        this.attackPower = data.attackPower();
        this.defense = data.defense();
        this.skillAmp = data.skillAmp();
        this.adaptiveForce = data.adaptiveForce();
        this.ccTimeToPlayer = data.ccTimeToPlayer();
        this.attackSpeed = data.attackSpeed();
        this.moveSpeed = data.moveSpeed();
        this.coolDownReduction = data.coolDownReduction();
        this.criticalStrikeChance = data.criticalStrikeChance();
        this.lifeSteal = data.lifeSteal();
        this.attackRange = data.attackRange();
        this.sightRange = data.sightRange();
    }
}
