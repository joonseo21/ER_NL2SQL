package io.eranalytics.pipeline.collection.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@EqualsAndHashCode
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TierId implements Serializable {
    @Column(name = "season_id")
    private Integer seasonId;

    @Column(name = "tier", columnDefinition = "text")
    private String tier;

    @Column(name = "division")
    private Short division;
}
