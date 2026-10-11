package io.eranalytics.pipeline.collection.metadata;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NameMetadataMapperTest {
    private NameMetadataBatch mapped() {
        return NameMetadataMapper.map(
                MetadataFixture.tables(),
                KoreanLocalization.parse(MetadataFixture.localization()),
                Instant.EPOCH);
    }

    @Test
    void mapsAllNameSpacesAndRetainsLocalizedHistoricalCodes() {
        NameMetadataBatch batch = mapped();
        assertThat(batch.names().keySet()).containsExactlyInAnyOrder(NameKind.values());
        assertThat(batch.names().get(NameKind.ITEM))
                .extracting(MetadataName::code)
                .containsExactlyInAnyOrder(100001, 200001, 900001);
        assertThat(batch.names().get(NameKind.TRAIT))
                .extracting(MetadataName::code)
                .contains(799001);
        assertThat(batch.names().get(NameKind.WEATHER))
                .extracting(MetadataName::code)
                .containsExactly(10001);
        MetadataName item =
                batch.names().get(NameKind.ITEM).stream()
                        .filter(row -> row.code() == 200001)
                        .findFirst()
                        .orElseThrow();
        assertThat(item.details().equipmentType()).isEqualTo("Head");
        assertThat(item.details().grade()).isEqualTo("Legend");
        assertThat(item.details().gradeKo()).isEqualTo("전설");
        assertThat(batch.names().get(NameKind.TRAIT).getFirst().details().traitGroup())
                .isEqualTo("Havoc");
    }

    @Test
    void currentTacticalStartSkillWinsOverOldIconAndStaticTable() {
        List<MetadataName> tactical = mapped().names().get(NameKind.TACTICAL);
        assertThat(
                        tactical.stream()
                                .filter(row -> row.code() == 120)
                                .findFirst()
                                .orElseThrow()
                                .nameKo())
                .isEqualTo("현재 전술 스킬");
        assertThat(
                        tactical.stream()
                                .filter(row -> row.code() == 140)
                                .findFirst()
                                .orElseThrow()
                                .nameKo())
                .isEqualTo("삭제된 전술 스킬");
    }

    @Test
    void missingTacticalNameIsNotGuessedFromIconOrOldMapping() {
        HashMap<String, String> localization =
                new java.util.HashMap<>(KoreanLocalization.parse(MetadataFixture.localization()));
        localization.remove("Skill/Group/Name/4114000");
        NameMetadataBatch batch =
                NameMetadataMapper.map(MetadataFixture.tables(), localization, Instant.EPOCH);
        assertThat(batch.names().get(NameKind.TACTICAL))
                .extracting(MetadataName::code)
                .doesNotContain(120);
        assertThat(batch.unresolved()).isPositive();
    }

    @Test
    void compoundTacticalSkillsWithDifferentGroupsRemainUnresolved() {
        Map<String, JsonNode> tables = MetadataFixture.tables();
        ((ObjectNode) tables.get("TacticalSkillSet").get("data").get(0))
                .put("skillCode", "4114001, 4110001");
        NameMetadataBatch batch =
                NameMetadataMapper.map(
                        tables,
                        KoreanLocalization.parse(MetadataFixture.localization()),
                        Instant.EPOCH);
        assertThat(batch.names().get(NameKind.TACTICAL))
                .extracting(MetadataName::code)
                .doesNotContain(120);
    }

    @Test
    void rejectsDuplicateAndLossySourceCodes() {
        Map<String, JsonNode> duplicate = MetadataFixture.tables();
        ArrayNode rows = (ArrayNode) duplicate.get("Area").get("data");
        rows.add(rows.get(0).deepCopy());
        assertThatThrownBy(
                        () ->
                                NameMetadataMapper.map(
                                        duplicate,
                                        KoreanLocalization.parse(MetadataFixture.localization()),
                                        Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
        Map<String, JsonNode> tables = MetadataFixture.tables();
        ((ObjectNode) tables.get("Trait").get("data").get(0)).put("code", 1.5);
        Map<String, JsonNode> malformed = tables;
        assertThatThrownBy(
                        () ->
                                NameMetadataMapper.map(
                                        malformed,
                                        KoreanLocalization.parse(MetadataFixture.localization()),
                                        Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void officialWeaponCodesIncludePolearmAndNonWeaponMasteriesAreExcluded() {
        assertThat(NameMetadataMapper.WEAPONS)
                .hasSize(24)
                .containsEntry(16, "TwoHandSword")
                .containsEntry(17, "Polearm")
                .doesNotContainKeys(12, 101, 102, 103, 201, 202);
    }

    @Test
    void localizationPreservesValuesAndAcceptsBomButRejectsConflict() {
        assertThat(KoreanLocalization.parse("\uFEFFItem/Name/1┃이름┃추가\nOther┃값").get("Item/Name/1"))
                .isEqualTo("이름┃추가");
        assertThatThrownBy(() -> KoreanLocalization.parse("Key┃첫 이름\nKey┃다른 이름"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KoreanLocalization.parse("no delimiter"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
