package io.eranalytics.pipeline.collection.metadata;

import java.util.Set;

/** Distinct name codes from a single eligible game, before or after persistence. */
public record MatchNameCodes(Set<Integer> items, Set<Integer> traits, Set<Integer> tacticalSkills) {
    public MatchNameCodes {
        items = Set.copyOf(items);
        traits = Set.copyOf(traits);
        tacticalSkills = Set.copyOf(tacticalSkills);
    }
}
