package io.eranalytics.pipeline.collection.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import io.eranalytics.pipeline.collection.metadata.MetadataName;
import java.time.Instant;

@Entity
@Table(name = "characters")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CharacterEntity {
    @Id
    @Column(name = "character_code")
    private Integer characterCode;

    @Column(name = "name_ko", columnDefinition = "text")
    private String nameKo;

    @Column(name = "name_en", columnDefinition = "text")
    private String nameEn;

    @Column(columnDefinition = "text")
    private String source;
    @Column(name = "l10n_key", columnDefinition = "text")
    private String l10nKey;
    @Column(name = "checked_at")
    private Instant checkedAt;

    public CharacterEntity(int code) { this.characterCode = code; }

    public void applyMetadata(MetadataName row, Instant checkedAt) {
        this.nameKo = row.nameKo();
        if (row.details().type() != null) this.nameEn = row.details().type();
        this.source = row.source();
        this.l10nKey = row.l10nKey();
        this.checkedAt = checkedAt;
    }
}
