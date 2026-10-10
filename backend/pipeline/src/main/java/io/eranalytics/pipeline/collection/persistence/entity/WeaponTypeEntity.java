package io.eranalytics.pipeline.collection.persistence.entity;

import io.eranalytics.pipeline.collection.metadata.MetadataName;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name="weapon_types")
public class WeaponTypeEntity extends NamedMetadataEntity {
    @Column(name="type_key", columnDefinition="text") private String typeKey;

    @Override
    protected void applyDetails(MetadataName.Details details) {
        if (details.type() != null) this.typeKey = details.type();
    }
}

