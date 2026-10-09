package io.eranalytics.pipeline.collection.persistence.entity;

import com.fasterxml.jackson.databind.JsonNode;
import io.eranalytics.pipeline.erapi.dto.LoadoutDto;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LoadoutFields {
    @Column(name = "trait_first_core")
    private Integer traitFirstCore;

    @Column(name = "tactical_skill_group")
    private Integer tacticalSkillGroup;

    @Column(name = "tactical_skill_level")
    private Integer tacticalSkillLevel;

    @Column(name = "route_id_of_start")
    private Integer routeIdOfStart;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "skill_level_info", columnDefinition = "jsonb")
    private JsonNode skillLevelInfo;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "credit_source", columnDefinition = "jsonb")
    private JsonNode creditSource;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "kill_monsters", columnDefinition = "jsonb")
    private JsonNode killMonsters;

    @Column(name = "place_of_start")
    private Integer placeOfStart;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "skill_order", columnDefinition = "integer[]")
    private Integer[] skillOrder;

    public LoadoutFields(LoadoutDto data) {
        this.traitFirstCore = data.traitFirstCore();
        this.tacticalSkillGroup = data.tacticalSkillGroup();
        this.tacticalSkillLevel = data.tacticalSkillLevel();
        this.routeIdOfStart = data.routeIdOfStart();
        this.skillLevelInfo = data.skillLevelInfo() == null ? null : data.skillLevelInfo().deepCopy();
        this.creditSource = data.creditSource() == null ? null : data.creditSource().deepCopy();
        this.killMonsters = data.killMonsters() == null ? null : data.killMonsters().deepCopy();
        this.placeOfStart = data.placeOfStart();
        this.skillOrder = data.skillOrder() == null ? null : data.skillOrder().toArray(Integer[]::new);
    }
}
