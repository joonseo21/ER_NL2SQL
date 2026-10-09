package io.eranalytics.pipeline.collection.persistence.entity;

import io.eranalytics.pipeline.erapi.dto.ParticipantCoreDto;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ParticipantCoreFields {
    @Column(name = "game_id")
    private Long gameId;

    @Column(name = "nickname", columnDefinition = "text")
    private String nickname;

    @Column(name = "team_number")
    private Integer teamNumber;

    @Column(name = "character_num")
    private Integer characterNum;

    @Column(name = "best_weapon")
    private Integer bestWeapon;

    @Column(name = "best_weapon_level")
    private Integer bestWeaponLevel;

    @Column(name = "game_rank")
    private Integer gameRank;

    @Column(name = "player_kill")
    private Integer playerKill;

    @Column(name = "player_assistant")
    private Integer playerAssistant;

    @Column(name = "monster_kill")
    private Integer monsterKill;

    @Column(name = "damage_to_player")
    private Integer damageToPlayer;

    @Column(name = "mmr_before")
    private Integer mmrBefore;

    @Column(name = "mmr_gain")
    private Integer mmrGain;

    @Column(name = "mmr_after")
    private Integer mmrAfter;

    @Column(name = "play_time")
    private Integer playTime;

    @Column(name = "victory")
    private Integer victory;

    @Column(name = "player_deaths")
    private Integer playerDeaths;

    @Column(name = "team_kill")
    private Integer teamKill;

    @Column(name = "total_field_kill")
    private Integer totalFieldKill;

    @Column(name = "give_up")
    private Integer giveUp;

    @Column(name = "escape_state")
    private Integer escapeState;

    @Column(name = "mmr_gain_in_game")
    private Integer mmrGainInGame;

    @Column(name = "mmr_loss_entry_cost")
    private Integer mmrLossEntryCost;

    @Column(name = "mmr_gain_gambit")
    private Integer mmrGainGambit;

    @Column(name = "watch_time")
    private Integer watchTime;

    @Column(name = "total_time")
    private Integer totalTime;

    @Column(name = "duration")
    private Integer duration;

    @Column(name = "use_emoticon_count")
    private Integer useEmoticonCount;

    @Column(name = "gambit")
    private Boolean gambit;

    @Column(name = "kings_gambit")
    private Boolean kingsGambit;

    @Column(name = "kill_gamma")
    private Boolean killGamma;

    public ParticipantCoreFields(ParticipantCoreDto data) {
        this.gameId = data.gameId();
        this.nickname = data.nickname();
        this.teamNumber = data.teamNumber();
        this.characterNum = data.characterNum();
        this.bestWeapon = data.bestWeapon();
        this.bestWeaponLevel = data.bestWeaponLevel();
        this.gameRank = data.gameRank();
        this.playerKill = data.playerKill();
        this.playerAssistant = data.playerAssistant();
        this.monsterKill = data.monsterKill();
        this.damageToPlayer = data.damageToPlayer();
        this.mmrBefore = data.mmrBefore();
        this.mmrGain = data.mmrGain();
        this.mmrAfter = data.mmrAfter();
        this.playTime = data.playTime();
        this.victory = data.victory();
        this.playerDeaths = data.playerDeaths();
        this.teamKill = data.teamKill();
        this.totalFieldKill = data.totalFieldKill();
        this.giveUp = data.giveUp();
        this.escapeState = data.escapeState();
        this.mmrGainInGame = data.mmrGainInGame();
        this.mmrLossEntryCost = data.mmrLossEntryCost();
        this.mmrGainGambit = data.mmrGainGambit();
        this.watchTime = data.watchTime();
        this.totalTime = data.totalTime();
        this.duration = data.duration();
        this.useEmoticonCount = data.useEmoticonCount();
        this.gambit = data.gambit();
        this.kingsGambit = data.kingsGambit();
        this.killGamma = data.killGamma();
    }
}
