package io.eranalytics.pipeline.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import lombok.Getter;

/** Usage fields are maintained by the usage store; name refresh must preserve them. */
@MappedSuperclass
@Getter
public abstract class MatchUsedMetadataEntity extends NamedMetadataEntity {
    @Column(name = "seen_in_matches", insertable = false, updatable = false)
    private Boolean seenInMatches;

    @Column(name = "usage_season_id", insertable = false, updatable = false)
    private Integer usageSeasonId;

    @Column(name = "usage_checked_at", insertable = false, updatable = false)
    private Instant usageCheckedAt;
}
