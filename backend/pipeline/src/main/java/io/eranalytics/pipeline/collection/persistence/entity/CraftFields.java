package io.eranalytics.pipeline.collection.persistence.entity;

import io.eranalytics.pipeline.erapi.dto.CraftDto;
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
public class CraftFields {
    @Column(name = "craft_uncommon")
    private Integer craftUncommon;

    @Column(name = "craft_rare")
    private Integer craftRare;

    @Column(name = "craft_epic")
    private Integer craftEpic;

    @Column(name = "craft_legend")
    private Integer craftLegend;

    @Column(name = "craft_mythic")
    private Integer craftMythic;

    @Column(name = "camp_fire_craft_uncommon")
    private Integer campFireCraftUncommon;

    @Column(name = "camp_fire_craft_rare")
    private Integer campFireCraftRare;

    @Column(name = "camp_fire_craft_epic")
    private Integer campFireCraftEpic;

    @Column(name = "camp_fire_craft_legendary")
    private Integer campFireCraftLegendary;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "food_craft_count", columnDefinition = "integer[]")
    private Integer[] foodCraftCount;

    public CraftFields(CraftDto data) {
        this.craftUncommon = data.craftUncommon();
        this.craftRare = data.craftRare();
        this.craftEpic = data.craftEpic();
        this.craftLegend = data.craftLegend();
        this.craftMythic = data.craftMythic();
        this.campFireCraftUncommon = data.campFireCraftUncommon();
        this.campFireCraftRare = data.campFireCraftRare();
        this.campFireCraftEpic = data.campFireCraftEpic();
        this.campFireCraftLegendary = data.campFireCraftLegendary();
        this.foodCraftCount = data.foodCraftCount() == null ? null : data.foodCraftCount().toArray(Integer[]::new);
    }
}
