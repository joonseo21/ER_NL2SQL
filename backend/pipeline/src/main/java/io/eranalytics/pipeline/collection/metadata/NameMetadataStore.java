package io.eranalytics.pipeline.collection.metadata;

/** Name snapshot storage and the character-name fallback when localization is unavailable. */
public interface NameMetadataStore {
    void save(NameMetadataBatch batch);

    void saveCharacterNames(int code, String nameKo, String nameEn);
}
