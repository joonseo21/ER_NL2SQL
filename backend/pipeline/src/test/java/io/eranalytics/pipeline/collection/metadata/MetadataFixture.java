package io.eranalytics.pipeline.collection.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;

public final class MetadataFixture {
    private MetadataFixture() {}

    public static Map<String, JsonNode> tables() {
        Map<String, String> inputs =
                Map.of(
                        "Character", "[{\"code\":1,\"name\":\"Synthetic\"}]",
                        "WeaponTypeInfo", "[{\"type\":\"Glove\"}]",
                        "ItemWeapon",
                                "[{\"code\":100001,\"itemType\":\"Weapon\",\"weaponType\":\"Glove\",\"itemGrade\":\"Epic\"}]",
                        "ItemArmor",
                                "[{\"code\":200001,\"itemType\":\"Armor\",\"armorType\":\"Head\",\"itemGrade\":\"Legend\"}]",
                        "Trait",
                                "[{\"code\":700001,\"traitType\":\"Core\",\"traitGroup\":\"Havoc\",\"active\":true}]",
                        "TacticalSkillSet", "[{\"code\":121,\"skillCode\":4114001}]",
                        "TacticalSkillSetGroup",
                                "[{\"group\":120,\"startCode\":121,\"icon\":\"VSkillIcon_4110000\",\"active\":true}]",
                        "Area", "[{\"code\":10,\"name\":\"Harbor\"}]");
        Map<String, JsonNode> result = new LinkedHashMap<>();
        inputs.forEach(
                (key, json) -> {
                    try {
                        result.put(
                                key,
                                new ObjectMapper()
                                        .readTree("{\"code\":200,\"data\":" + json + "}"));
                    } catch (Exception error) {
                        throw new IllegalStateException(error);
                    }
                });
        return result;
    }

    public static String localization() {
        return """
                Character/Name/1┃가상 실험체
                MasteryType/Glove┃가상 글러브
                MasteryType/TwoHandSword┃가상 양손검
                Item/Name/100001┃가상 무기
                Item/Name/200001┃가상 방어구
                Item/Name/900001┃과거 아이템
                ItemGrade/Epic┃영웅
                ItemGrade/Legend┃전설
                Trait/Name/700001┃가상 특성
                Trait/Name/799001┃과거 특성
                Skill/Group/Name/4114000┃현재 전술 스킬
                Skill/Group/Name/4110000┃오래된 아이콘 이름
                Skill/Group/Name/4113000┃삭제된 전술 스킬
                Skill/Group/Name/3003000┃가상 무기 스킬
                Area/Name/Harbor┃가상 항구
                Weather/Name/10001┃가상 날씨
                Weather/Name/0┃없음
                """;
    }
}
