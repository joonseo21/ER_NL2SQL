package io.eranalytics.pipeline.persistence.metadata;

import io.eranalytics.pipeline.collection.metadata.MetadataName;
import io.eranalytics.pipeline.collection.metadata.NameKind;
import io.eranalytics.pipeline.collection.metadata.NameMetadataBatch;
import io.eranalytics.pipeline.collection.metadata.NameMetadataStore;
import io.eranalytics.pipeline.persistence.entity.AreaNameEntity;
import io.eranalytics.pipeline.persistence.entity.CharacterEntity;
import io.eranalytics.pipeline.persistence.entity.ItemNameEntity;
import io.eranalytics.pipeline.persistence.entity.NamedMetadataEntity;
import io.eranalytics.pipeline.persistence.entity.SkillGroupEntity;
import io.eranalytics.pipeline.persistence.entity.TacticalSkillEntity;
import io.eranalytics.pipeline.persistence.entity.TraitNameEntity;
import io.eranalytics.pipeline.persistence.entity.WeaponTypeEntity;
import io.eranalytics.pipeline.persistence.entity.WeatherTypeEntity;
import jakarta.persistence.EntityManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One transaction for the resolved snapshot. No deletion of absent source codes. */
@Service
public class JpaNameMetadataStore implements NameMetadataStore {
    private final EntityManager entities;
    private final JdbcTemplate jdbc;

    public JpaNameMetadataStore(EntityManager entities, JdbcTemplate jdbc) {
        this.entities = entities;
        this.jdbc = jdbc;
    }

    @Override
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

    @Override
    public void saveCharacterNames(int code, String nameKo, String nameEn) {
        jdbc.update(
                """
                INSERT INTO characters(character_code, name_ko, name_en)
                VALUES (?, ?, ?)
                ON CONFLICT (character_code) DO UPDATE SET
                  name_ko = coalesce(EXCLUDED.name_ko, characters.name_ko),
                  name_en = coalesce(EXCLUDED.name_en, characters.name_en)
                """,
                code,
                nameKo,
                nameEn);
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
