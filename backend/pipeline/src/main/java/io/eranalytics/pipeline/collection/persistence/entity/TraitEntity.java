package io.eranalytics.pipeline.collection.persistence.entity;

import io.eranalytics.pipeline.collection.model.TraitRow;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "participant_traits")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TraitEntity extends AssignedIdEntity<TraitId> {
    @EmbeddedId
    private TraitId id;


    public TraitEntity(long participantId, TraitRow row) {
        this.id = new TraitId(participantId, row.slotType(), row.traitCode());

    }

    public void updateFrom(TraitEntity row) {
        // The trait's entire value is its immutable key.
    }
}
