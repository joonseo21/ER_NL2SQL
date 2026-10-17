package io.eranalytics.pipeline.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "route_paths")
@NoArgsConstructor
public class RoutePathEntity {
    @EmbeddedId private RoutePathId id;

    @Column(name = "area_code")
    private Integer areaCode;

    public RoutePathEntity(int seasonId, int routeId, int seq, int areaCode) {
        this.id = new RoutePathId(seasonId, routeId, seq);
        this.areaCode = areaCode;
    }
}
