package io.eranalytics.pipeline.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Embeddable
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
public class RoutePathId implements Serializable {
    @Column(name = "season_id")
    private Integer seasonId;

    @Column(name = "route_id")
    private Integer routeId;

    private Integer seq;
}
