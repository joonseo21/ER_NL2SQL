package io.eranalytics.pipeline.erapi;

import static io.eranalytics.pipeline.erapi.jackson.BattleJsonValues.invalid;
import static io.eranalytics.pipeline.erapi.jackson.BattleJsonValues.requireObject;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.eranalytics.pipeline.erapi.dto.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Decodes flat response fields into typed groups without discarding original JSON. */
public final class BattleUserResultReader {
    private static final List<Class<?>> FIELD_TYPES = List.of(
            GameResultDto.class, ParticipantCoreDto.class, CombatDto.class,
            CharacterStatsDto.class, CreditDto.class, CraftDto.class, ActivityDto.class,
            LoadoutDto.class, EquipmentDto.class, TraitsDto.class, MasteryDto.class,
            MatchupDto.class, DeathDto.class);

    private final Map<Class<?>, ObjectReader> readers;
    private final Map<Class<?>, Set<String>> fieldNames;

    public BattleUserResultReader(ObjectMapper mapper) {
        // Snapshot the configuration so subsequent caller changes cannot change mapping.
        ObjectMapper json = mapper.copy();
        readers = FIELD_TYPES.stream().collect(Collectors.toUnmodifiableMap(
                Function.identity(), json::readerFor));
        fieldNames = FIELD_TYPES.stream().collect(Collectors.toUnmodifiableMap(
                Function.identity(), type -> {
                    Set<String> names = new LinkedHashSet<>();
                    json.getDeserializationConfig().introspect(json.constructType(type))
                            .findProperties().forEach(property -> names.add(property.getName()));
                    return Collections.unmodifiableSet(names);
                }));
    }

    /**
     * BattleUserResult api response json을 BattleUserResultDto로 매핑
     * @param node
     * @return
     */
    public BattleUserResultDto read(JsonNode node) {
        requireObject(node, "userGames");
        GameResultDto game = read(node, GameResultDto.class, "");
        ParticipantCoreDto core = read(node, ParticipantCoreDto.class, "");
        if (game.gameId() == null) {
            throw invalid("gameId");
        }
        if (core.nickname() == null || core.nickname().isBlank()) {
            throw invalid("nickname");
        }
        return new BattleUserResultDto(game, core,
                read(node, CombatDto.class, ""),
                read(node, CharacterStatsDto.class, ""),
                read(node, CreditDto.class, ""),
                read(node, CraftDto.class, ""),
                read(node, ActivityDto.class, ""),
                read(node, LoadoutDto.class, ""),
                read(node, EquipmentDto.class, ""),
                read(node, TraitsDto.class, ""),
                read(node, MasteryDto.class, ""),
                read(node, MatchupDto.class, ""),
                deaths((ObjectNode) node), (ObjectNode) node);
    }

    public Set<String> resultFieldNames() {
        Set<String> names = new LinkedHashSet<>();
        FIELD_TYPES.stream().filter(type -> type != DeathDto.class)
                .forEach(type -> names.addAll(fieldNames.get(type)));
        return Collections.unmodifiableSet(names);
    }

    public Set<String> deathFieldNames() {
        return fieldNames.get(DeathDto.class);
    }

    private List<DeathDto> deaths(ObjectNode raw) {
        List<DeathDto> deaths = new ArrayList<>();
        for (String suffix : List.of("", "2", "3")) {
            ObjectNode selected = raw.objectNode();
            for (String name : fieldNames.get(DeathDto.class)) {
                JsonNode value = raw.get(name + suffix);
                if (value != null) {
                    selected.set(name, value);
                }
            }
            // Non-player killDetail is preserved raw, not interpreted as a nickname.
            if (!"player".equals(selected.path("killer").asText())) {
                selected.remove("killDetail");
            }
            deaths.add(read(selected, DeathDto.class, suffix));
        }
        return List.copyOf(deaths);
    }

    // 각 타입에 맞는 ObjectMapper를 읽는 용도
    // suffix는 에러시에 사용
    private <T> T read(JsonNode node, Class<T> type, String suffix) {
        try {
            return readers.get(type).readValue(node);
        } catch (IOException exception) {
            String field = "userGames";
            if (exception instanceof JsonMappingException mapping) {
                // Only the top-level field is safe to log; never echo values or map keys.
                field = mapping.getPath().stream().map(JsonMappingException.Reference::getFieldName)
                        .filter(name -> name != null).findFirst().orElse(field);
            }
            throw invalid(field + suffix);
        }
    }
}
