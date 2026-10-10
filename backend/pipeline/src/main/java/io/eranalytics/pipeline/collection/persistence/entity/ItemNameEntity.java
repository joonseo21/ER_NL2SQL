package io.eranalytics.pipeline.collection.persistence.entity;

import io.eranalytics.pipeline.collection.metadata.MetadataName;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name="items")
public class ItemNameEntity extends NamedMetadataEntity {
    @Column(name="item_type", columnDefinition="text") private String itemType;
    @Column(name="item_grade", columnDefinition="text") private String itemGrade;
    @Column(name="grade_name_ko", columnDefinition="text") private String gradeNameKo;
    @Column(name="equipment_type", columnDefinition="text") private String equipmentType;

    @Override
    protected void applyDetails(MetadataName.Details details) {
        if (details.type() != null) this.itemType = details.type();
        if (details.grade() != null) this.itemGrade = details.grade();
        if (details.gradeKo() != null) this.gradeNameKo = details.gradeKo();
        if (details.equipmentType() != null) this.equipmentType = details.equipmentType();
    }
}

