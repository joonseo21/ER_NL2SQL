package io.eranalytics.pipeline.collection.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Code/name extraction only. No patch stats, player data or inferred skill-order aliases. */
public final class NameMetadataMapper {
    public static final List<String> TABLES = List.of("Character", "WeaponTypeInfo", "ItemWeapon",
            "ItemArmor", "Trait", "TacticalSkillSet", "TacticalSkillSetGroup", "Area");
    // Official mastery codes; the API type table does not carry these numeric IDs.
    public static final Map<Integer, String> WEAPONS = Map.ofEntries(
            Map.entry(1,"Glove"), Map.entry(2,"Tonfa"), Map.entry(3,"Bat"), Map.entry(4,"Whip"),
            Map.entry(5,"HighAngleFire"), Map.entry(6,"DirectFire"), Map.entry(7,"Bow"),
            Map.entry(8,"CrossBow"), Map.entry(9,"Pistol"), Map.entry(10,"AssaultRifle"),
            Map.entry(11,"SniperRifle"), Map.entry(13,"Hammer"), Map.entry(14,"Axe"),
            Map.entry(15,"OneHandSword"), Map.entry(16,"TwoHandSword"), Map.entry(17,"Polearm"),
            Map.entry(18,"DualSword"), Map.entry(19,"Spear"), Map.entry(20,"Nunchaku"),
            Map.entry(21,"Rapier"), Map.entry(22,"Guitar"), Map.entry(23,"Camera"),
            Map.entry(24,"Arcana"), Map.entry(25,"VFArm"));
    // Only for codes absent from the current table, never override current startCode/skillCode.
    private static final Map<Integer,Integer> LEGACY_TACTICAL = Map.ofEntries(
            Map.entry(30,4000000), Map.entry(40,4001000), Map.entry(50,4101000),
            Map.entry(60,4102000), Map.entry(70,4103000), Map.entry(80,4104000),
            Map.entry(90,4105000), Map.entry(110,4107000), Map.entry(120,4110000),
            Map.entry(130,4112000), Map.entry(140,4113000), Map.entry(150,4108000));

    private NameMetadataMapper() {}

    public static NameMetadataBatch map(Map<String,JsonNode> tables, Map<String,String> localization,
                                        Instant checkedAt) {
        var names = new EnumMap<NameKind,List<MetadataName>>(NameKind.class);
        for (NameKind kind : NameKind.values()) names.put(kind, new ArrayList<>());
        int[] missing = {0};
        for (String table : TABLES) index(tables, table, table.equals("WeaponTypeInfo") ? "type"
                : table.equals("TacticalSkillSetGroup") ? "group" : "code");

        for (JsonNode row : rows(tables,"Character")) {
            add(names, NameKind.CHARACTER, number(row,"code"), "Character/Name/" + number(row,"code"),
                    "/v2/data/Character", new MetadataName.Details(text(row,"name"),null,null,null,null,null,null),
                    localization,missing);
        }
        WEAPONS.forEach((code,type) -> add(names,NameKind.WEAPON,code,"MasteryType/"+type,
                "official mastery code table + /v1/l10n/Korean",
                new MetadataName.Details(type,null,null,null,null,null,null),localization,missing));

        var items = new LinkedHashMap<Integer,JsonNode>();
        for (String table : List.of("ItemWeapon","ItemArmor")) {
            for (JsonNode row : rows(tables,table)) {
                if (items.putIfAbsent(number(row,"code"),row) != null)
                    throw new IllegalArgumentException("Duplicate item codes across tables");
            }
        }
        localized(names,NameKind.ITEM,"Item/Name/",localization, (code) -> {
            var row = items.get(code);
            if (row == null) return MetadataName.Details.EMPTY;
            String grade = text(row,"itemGrade");
            return new MetadataName.Details(text(row,"itemType"),grade,
                    localization.get("ItemGrade/"+grade),
                    row.has("armorType") ? text(row,"armorType") : text(row,"weaponType"),null,null,null);
        });
        var traits = integerIndex(tables,"Trait","code");
        localized(names,NameKind.TRAIT,"Trait/Name/",localization, code -> {
            var row = traits.get(code);
            return row == null ? MetadataName.Details.EMPTY
                    : new MetadataName.Details(text(row,"traitType"),null,null,null,
                    null,text(row,"traitGroup"),bool(row,"active"));
        });
        localized(names,NameKind.SKILL_GROUP,"Skill/Group/Name/",localization, code -> MetadataName.Details.EMPTY);
        localized(names,NameKind.WEATHER,"Weather/Name/",localization, code -> MetadataName.Details.EMPTY);

        var sets = integerIndex(tables,"TacticalSkillSet","code");
        var groups = integerIndex(tables,"TacticalSkillSetGroup","group");
        for (var entry : groups.entrySet()) {
            var row = entry.getValue();
            var start = sets.get(number(row,"startCode"));
            if (start == null) throw new IllegalArgumentException("Missing tactical start set");
            List<Integer> candidates = skillCodes(start.get("skillCode")).stream()
                    .map(code -> code - code % 10).distinct().toList();
            // Only resolve a single verified localization key; ambiguous compound skills stay unknown.
            if (candidates.size() != 1) { missing[0]++; continue; }
            int groupCode = candidates.getFirst();
            add(names,NameKind.TACTICAL,entry.getKey(),"Skill/Group/Name/"+groupCode,
                    "/v2/data/TacticalSkillSetGroup + TacticalSkillSet",
                    new MetadataName.Details(null,null,null,null,groupCode,null,bool(row,"active")),
                    localization,missing);
        }
        LEGACY_TACTICAL.forEach((code,groupCode) -> {
            if (!groups.containsKey(code)) add(names,NameKind.TACTICAL,code,"Skill/Group/Name/"+groupCode,
                    "official tactical code table + /v1/l10n/Korean",
                    new MetadataName.Details(null,null,null,null,groupCode,null,null),localization,missing);
        });
        for (JsonNode row : rows(tables,"Area")) {
            add(names,NameKind.AREA,number(row,"code"),"Area/Name/"+text(row,"name"),"/v2/data/Area",
                    new MetadataName.Details(text(row,"name"),null,null,null,null,null,null),localization,missing);
        }
        return new NameMetadataBatch(checkedAt,names,missing[0]);
    }

    private static void localized(Map<NameKind,List<MetadataName>> output, NameKind kind, String prefix,
                                  Map<String,String> names, java.util.function.IntFunction<MetadataName.Details> details) {
        new TreeMap<>(names).forEach((key,value) -> {
            if (!key.startsWith(prefix)) return;
            String suffix = key.substring(prefix.length());
            if (!suffix.matches("[0-9]+")) return;
            int code;
            try { code = Integer.parseInt(suffix); }
            catch (NumberFormatException error) { throw new IllegalArgumentException("Localization code out of range"); }
            if (code > 0 && !value.isBlank()) output.get(kind).add(new MetadataName(code,value,
                    "/v1/l10n/Korean",key,details.apply(code)));
        });
    }

    private static void add(Map<NameKind,List<MetadataName>> output, NameKind kind, int code, String key,
                            String source, MetadataName.Details details, Map<String,String> names, int[] missing) {
        if (code <= 0) return;
        String value = names.get(key);
        if (value == null || value.isBlank()) { missing[0]++; return; }
        output.get(kind).add(new MetadataName(code,value,source,key,details));
    }

    private static JsonNode rows(Map<String,JsonNode> tables, String table) {
        JsonNode payload = tables.get(table);
        if (payload == null || !payload.path("data").isArray() || payload.path("data").isEmpty())
            throw new IllegalArgumentException("Missing metadata table: "+table);
        return payload.get("data");
    }
    private static Map<String,JsonNode> index(Map<String,JsonNode> tables, String table, String field) {
        Map<String,JsonNode> result = new LinkedHashMap<>();
        for (JsonNode row : rows(tables,table)) {
            String key = field.equals("type") ? text(row,field) : Integer.toString(number(row,field));
            if (result.putIfAbsent(key,row) != null) throw new IllegalArgumentException("Duplicate metadata key: "+table);
        }
        return result;
    }
    private static Map<Integer,JsonNode> integerIndex(Map<String,JsonNode> tables,String table,String field) {
        Map<Integer,JsonNode> result = new LinkedHashMap<>();
        index(tables,table,field).forEach((key,row) -> result.put(Integer.parseInt(key),row));
        return result;
    }
    private static int number(JsonNode row,String field) {
        var value = row.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt())
            throw new IllegalArgumentException("Invalid metadata number: "+field);
        return value.intValue();
    }
    private static String text(JsonNode row,String field) {
        var value = row.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank())
            throw new IllegalArgumentException("Invalid metadata text: "+field);
        return value.textValue();
    }
    private static Boolean bool(JsonNode row,String field) {
        var value = row.get(field);
        if (value == null || !value.isBoolean()) throw new IllegalArgumentException("Invalid metadata boolean: "+field);
        return value.booleanValue();
    }
    private static List<Integer> skillCodes(JsonNode value) {
        if (value == null) throw new IllegalArgumentException("Missing tactical skill code");
        if (value.isIntegralNumber() && value.canConvertToInt()) return List.of(value.intValue());
        if (value.isTextual() && value.textValue().matches("[0-9]+(\\s*,\\s*[0-9]+)*")) {
            try { return java.util.Arrays.stream(value.textValue().split(","))
                    .map(String::trim).map(Integer::parseInt).toList(); }
            catch (NumberFormatException error) { throw new IllegalArgumentException("Tactical skill code out of range"); }
        }
        throw new IllegalArgumentException("Invalid tactical skill code");
    }
}

