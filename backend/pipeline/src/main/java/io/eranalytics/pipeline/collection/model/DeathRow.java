package io.eranalytics.pipeline.collection.model;

/** killerName is the API character/animal name; participant indexes become DB IDs in step 2. */
public record DeathRow(short deathSequence, String killerType, Integer killerParticipantIndex,
                Integer killerCharacterNum, String killerName, String killerWeapon,
                String causeOfDeath, Integer placeOfDeath) {
}
