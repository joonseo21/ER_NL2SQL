package io.eranalytics.pipeline.collection.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

@Entity
@Table(name = "tiers")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TierEntity {
    @EmbeddedId
    private TierId id;

    @Column(name = "min_mmr", nullable = false)
    private Integer minMmr;

    @Column(name = "sort_order", nullable = false)
    private Short sortOrder;
}
