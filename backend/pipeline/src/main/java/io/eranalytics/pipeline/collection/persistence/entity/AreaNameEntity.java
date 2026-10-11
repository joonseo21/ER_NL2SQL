package io.eranalytics.pipeline.collection.persistence.entity;

import io.eranalytics.pipeline.collection.metadata.MetadataName;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "areas")
public class AreaNameEntity extends NamedMetadataEntity {
    @Column(name = "name_en", columnDefinition = "text")
    private String nameEn;

    @Override
    protected void applyDetails(MetadataName.Details details) {
        if (details.type() != null) this.nameEn = details.type();
    }
}
