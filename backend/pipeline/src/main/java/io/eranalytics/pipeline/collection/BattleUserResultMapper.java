package io.eranalytics.pipeline.collection;

import static io.eranalytics.pipeline.erapi.jackson.BattleJsonValues.invalid;

import com.fasterxml.jackson.databind.JsonNode;
import io.eranalytics.pipeline.collection.mapping.DeathMapper;
import io.eranalytics.pipeline.collection.mapping.EquipmentMapper;
import io.eranalytics.pipeline.collection.mapping.MasteryMapper;
import io.eranalytics.pipeline.collection.mapping.MatchupMapper;
import io.eranalytics.pipeline.collection.mapping.RawFieldPolicy;
import io.eranalytics.pipeline.collection.mapping.TraitMapper;
import io.eranalytics.pipeline.collection.model.MappedMatch;
import io.eranalytics.pipeline.collection.model.MappedParticipant;
import io.eranalytics.pipeline.collection.model.GameData;
import io.eranalytics.pipeline.collection.model.ParticipantData;
import io.eranalytics.pipeline.config.BattleResultObjectMapperFactory;
import io.eranalytics.pipeline.erapi.BattleUserResultReader;
import io.eranalytics.pipeline.erapi.dto.BattleUserResultDto;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Orchestrates typed response mapping; performs no database calls. */
public final class BattleUserResultMapper {
    private final BattleUserResultReader reader;
    private final RawFieldPolicy rawPolicy;

    public BattleUserResultMapper() {
        this(new BattleUserResultReader(BattleResultObjectMapperFactory.create()));
    }

    public BattleUserResultMapper(BattleUserResultReader reader) {
        this.reader = reader;
        this.rawPolicy = new RawFieldPolicy(reader);
    }

    public MappedMatch mapMatch(List<JsonNode> results, Map<String, Integer> characterCodesByName) {
        if (results == null || results.isEmpty()) {
            throw invalid("userGames");
        }
        List<BattleUserResultDto> inputs = results.stream().map(reader::read).toList();
        var game = inputs.getFirst().game();
        Map<String, Integer> indexes = new HashMap<>();
        Set<Integer> teams = new LinkedHashSet<>();
        for (int index = 0; index < inputs.size(); index++) {
            var input = inputs.get(index);
            if (!game.equals(input.game())) {
                throw invalid("shared game fields");
            }
            if (indexes.putIfAbsent(input.core().nickname(), index) != null) {
                throw invalid("duplicate nickname");
            }
            if (input.core().teamNumber() != null) {
                teams.add(input.core().teamNumber());
            }
        }
        DeathMapper deathMapper = new DeathMapper(inputs, indexes, characterCodesByName);
        List<MappedParticipant> participants = new ArrayList<>();
        for (var input : inputs) {
            ParticipantData data = new ParticipantData(input.core(), input.combat(), input.stats(),
                    input.credits(), input.crafting(), input.activity(), input.loadout(),
                    game.versionMajor(), game.versionMinor());
            participants.add(new MappedParticipant(data,
                    EquipmentMapper.map(input.equipment()),
                    TraitMapper.map(input.loadout().traitFirstCore(), input.traits()),
                    MasteryMapper.map(input.mastery()),
                    MatchupMapper.map(input.core().characterNum(), input.matchup()),
                    deathMapper.map(input.deaths()), rawPolicy.remaining(input.raw())));
        }
        return new MappedMatch(new GameData(game, teams.size()), List.copyOf(participants));
    }

    public Set<String> movedRawKeys() {
        return rawPolicy.movedKeys();
    }

}
