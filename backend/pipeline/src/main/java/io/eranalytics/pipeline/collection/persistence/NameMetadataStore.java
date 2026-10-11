package io.eranalytics.pipeline.collection.persistence;

import io.eranalytics.pipeline.collection.metadata.MetadataName;
import io.eranalytics.pipeline.collection.metadata.NameKind;
import io.eranalytics.pipeline.collection.metadata.NameMetadataBatch;
import io.eranalytics.pipeline.collection.persistence.entity.AreaNameEntity;
import io.eranalytics.pipeline.collection.persistence.entity.CharacterEntity;
import io.eranalytics.pipeline.collection.persistence.entity.ItemNameEntity;
import io.eranalytics.pipeline.collection.persistence.entity.NamedMetadataEntity;
import io.eranalytics.pipeline.collection.persistence.entity.SkillGroupEntity;
import io.eranalytics.pipeline.collection.persistence.entity.TacticalSkillEntity;
import io.eranalytics.pipeline.collection.persistence.entity.TraitNameEntity;
import io.eranalytics.pipeline.collection.persistence.entity.WeaponTypeEntity;
import io.eranalytics.pipeline.collection.persistence.entity.WeatherTypeEntity;
import jakarta.persistence.EntityManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One transaction for the resolved snapshot. No deletion of absent source codes. */
@Service
public class NameMetadataStore {
    private final EntityManager entities;

    public NameMetadataStore(EntityManager entities) {
        this.entities = entities;
    }

    @Transactional
    public void save(NameMetadataBatch batch) {
        for (NameKind kind : NameKind.values()) {
            List<MetadataName> rows = batch.names().get(kind);
            if (rows == null || rows.isEmpty()) continue;
            if (kind == NameKind.CHARACTER) {
                Map<Integer, CharacterEntity> existing = new HashMap<>();
                entities.createQuery("from CharacterEntity", CharacterEntity.class)
                        .getResultList()
                        .forEach(row -> existing.put(row.getCharacterCode(), row));
                for (MetadataName row : rows) {
                    CharacterEntity target = existing.get(row.code());
                    if (target == null) {
                        target = new CharacterEntity(row.code());
                        entities.persist(target);
                    }
                    target.applyMetadata(row, batch.checkedAt());
                }
            } else {
                Class<? extends NamedMetadataEntity> type = type(kind);
                Map<Integer, NamedMetadataEntity> existing = new HashMap<>();
                entities.createQuery("from " + type.getSimpleName(), type)
                        .getResultList()
                        .forEach(row -> existing.put(row.getCode(), row));
                for (MetadataName row : rows) {
                    NamedMetadataEntity target = existing.get(row.code());
                    if (target == null) {
                        try {
                            target = type.getConstructor().newInstance();
                        } catch (ReflectiveOperationException error) {
                            throw new IllegalStateException("Metadata entity construction failed");
                        }
                        target.apply(row, batch.checkedAt());
                        entities.persist(target);
                    } else target.apply(row, batch.checkedAt());
                }
            }
        }
        entities.flush();
    }

    private static Class<? extends NamedMetadataEntity> type(NameKind kind) {
        return switch (kind) {
            case WEAPON -> WeaponTypeEntity.class;
            case ITEM -> ItemNameEntity.class;
            case TRAIT -> TraitNameEntity.class;
            case TACTICAL -> TacticalSkillEntity.class;
            case SKILL_GROUP -> SkillGroupEntity.class;
            case AREA -> AreaNameEntity.class;
            case WEATHER -> WeatherTypeEntity.class;
            default -> throw new IllegalArgumentException("Unsupported metadata entity");
        };
    }
}
