package io.eranalytics.pipeline.collection.persistence.entity;

import io.eranalytics.pipeline.collection.model.MasteryRow;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "participant_mastery")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MasteryEntity extends AssignedIdEntity<MasteryId> {
    @EmbeddedId
    private MasteryId id;

    @Column(name = "mastery_level")
    private Integer masteryLevel;

    public MasteryEntity(long participantId, MasteryRow row) {
        this.id = new MasteryId(participantId, row.masteryCode());
        this.masteryLevel = row.masteryLevel();
    }

    public void updateFrom(MasteryEntity row) {
        this.masteryLevel = row.masteryLevel;
    }
}
