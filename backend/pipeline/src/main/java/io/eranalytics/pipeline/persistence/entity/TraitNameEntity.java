package io.eranalytics.pipeline.persistence.entity;

import io.eranalytics.pipeline.collection.metadata.MetadataName;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "trait_names")
public class TraitNameEntity extends NamedMetadataEntity {
    @Column(name = "trait_type", columnDefinition = "text")
    private String traitType;

    @Column(name = "trait_group", columnDefinition = "text")
    private String traitGroup;

    @Column(name = "active")
    private Boolean active;

    @Override
    protected void applyDetails(MetadataName.Details details) {
        if (details.type() != null) this.traitType = details.type();
        if (details.traitGroup() != null) this.traitGroup = details.traitGroup();
        if (details.active() != null) this.active = details.active();
    }
}
