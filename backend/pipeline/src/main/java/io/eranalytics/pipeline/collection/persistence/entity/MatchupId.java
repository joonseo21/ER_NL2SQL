package io.eranalytics.pipeline.collection.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@EqualsAndHashCode
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MatchupId implements Serializable {
    @Column(name = "participant_id")
    private Long participantId;

    @Column(name = "opponent_character_num")
    private Integer opponentCharacterNum;
}
