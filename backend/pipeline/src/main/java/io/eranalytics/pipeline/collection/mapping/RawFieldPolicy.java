package io.eranalytics.pipeline.collection.mapping;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.eranalytics.pipeline.erapi.BattleUserResultReader;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Remove only fields represented by the column/child DTO contract. */
public final class RawFieldPolicy {
    private static final Set<String> RETAINED_DEATH_FIELDS = Set.of("killDetail");
    private final Set<String> movedKeys;

    // killDetail, killDetail2, killDetail3과 같은 key 정리
    public RawFieldPolicy(BattleUserResultReader reader) {
        Set<String> keys = new LinkedHashSet<>(reader.resultFieldNames());
        for (String suffix : List.of("", "2", "3")) {
            reader.deathFieldNames().stream().filter(name -> !RETAINED_DEATH_FIELDS.contains(name))
                    .forEach(name -> keys.add(name + suffix));
        }
        movedKeys = Collections.unmodifiableSet(keys);
    }

    public Set<String> movedKeys() {
        return movedKeys;
    }

    public ObjectNode remaining(ObjectNode original) {
        ObjectNode remaining = original.deepCopy();
        remaining.remove(movedKeys);
        return remaining;
    }
}
