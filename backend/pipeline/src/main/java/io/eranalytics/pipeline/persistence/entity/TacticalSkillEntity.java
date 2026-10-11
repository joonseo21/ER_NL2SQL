package io.eranalytics.pipeline.persistence.entity;

import io.eranalytics.pipeline.collection.metadata.MetadataName;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "tactical_skills")
public class TacticalSkillEntity extends NamedMetadataEntity {
    @Column(name = "skill_group_code")
    private Integer skillGroupCode;

    @Override
    protected void applyDetails(MetadataName.Details details) {
        if (details.groupCode() != null) this.skillGroupCode = details.groupCode();
    }
}
