package io.eranalytics.pipeline.collection.persistence.entity;

import io.eranalytics.pipeline.collection.model.EquipmentRow;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "participant_equipment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EquipmentEntity extends AssignedIdEntity<EquipmentId> {
    @EmbeddedId
    private EquipmentId id;

    @Column(name = "grade")
    private Short grade;

    public EquipmentEntity(long participantId, EquipmentRow row) {
        this.id = new EquipmentId(participantId, row.kind(), row.slot(), row.itemCode());
        this.grade = row.grade();
    }

    public void updateFrom(EquipmentEntity row) {
        this.grade = row.grade;
    }
}
