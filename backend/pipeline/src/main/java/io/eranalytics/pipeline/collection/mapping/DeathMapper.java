package io.eranalytics.pipeline.collection.mapping;

import static io.eranalytics.pipeline.erapi.jackson.BattleJsonValues.emptyToNull;
import static io.eranalytics.pipeline.erapi.jackson.BattleJsonValues.invalid;

import io.eranalytics.pipeline.collection.model.DeathRow;
import io.eranalytics.pipeline.erapi.dto.BattleUserResultDto;
import io.eranalytics.pipeline.erapi.dto.DeathDto;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Resolves temporary participant indexes; the writer assigns DB IDs in step 2. */
public final class DeathMapper {
    private final List<BattleUserResultDto> participants;
    private final Map<String, Integer> indexes;
    private final Map<String, Integer> characterCodes = new HashMap<>();

    public DeathMapper(List<BattleUserResultDto> participants, Map<String, Integer> indexes,
                       Map<String, Integer> characterCodesByName) {
        this.participants = participants;
        this.indexes = indexes;
        characterCodesByName.forEach((name, code) ->
                characterCodes.merge(name.toLowerCase(Locale.ROOT), code, Math::min));
    }

    public List<DeathRow> map(List<DeathDto> deaths) {
        List<DeathRow> rows = new ArrayList<>();
        for (int index = 0; index < deaths.size(); index++) {
            DeathDto death = deaths.get(index);
            String type = emptyToNull(death.killer());
            String name = emptyToNull(death.killerCharacter());
            String weapon = emptyToNull(death.killerWeapon());
            String cause = emptyToNull(death.causeOfDeath());
            Integer place = death.placeOfDeath();
            if (type == null) {
                if (name != null || weapon != null || cause != null || place != null) {
                    throw invalid("killer" + (index == 0 ? "" : index + 1));
                }
                continue;
            }
            Integer killerIndex = null;
            Integer killerCharacter = null;
            if (type.equals("player")) {
                killerIndex = indexes.get(death.killDetail());
                if (killerIndex != null) {
                    killerCharacter = participants.get(killerIndex).core().characterNum();
                }
                if (killerCharacter == null && name != null) {
                    killerCharacter = characterCodes.get(name.toLowerCase(Locale.ROOT));
                }
            }
            rows.add(new DeathRow((short) (index + 1), type, killerIndex, killerCharacter,
                    name, weapon, cause, place));
        }
        return List.copyOf(rows);
    }
}
