package io.eranalytics.pipeline.collection.persistence.entity;

import io.eranalytics.pipeline.collection.model.MatchupRow;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "participant_matchups")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MatchupEntity extends AssignedIdEntity<MatchupId> {
    @EmbeddedId
    private MatchupId id;

    @Column(name = "character_num")
    private Integer characterNum;

    @Column(name = "kills")
    private Integer kills;

    @Column(name = "deaths")
    private Integer deaths;

    public MatchupEntity(long participantId, MatchupRow row) {
        this.id = new MatchupId(participantId, row.opponentCharacterNum());
        this.characterNum = row.characterNum();
        this.kills = row.kills();
        this.deaths = row.deaths();
    }

    public void updateFrom(MatchupEntity row) {
        this.characterNum = row.characterNum;
        this.kills = row.kills;
        this.deaths = row.deaths;
    }
}
