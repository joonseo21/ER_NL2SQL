package io.eranalytics.pipeline.persistence.entity;

import io.eranalytics.pipeline.collection.metadata.MetadataName;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import lombok.Getter;

@MappedSuperclass
@Getter
public abstract class NamedMetadataEntity {
    @Id private Integer code;

    @Column(name = "name_ko", columnDefinition = "text")
    private String nameKo;

    @Column(columnDefinition = "text")
    private String source;

    @Column(name = "l10n_key", columnDefinition = "text")
    private String l10nKey;

    @Column(name = "checked_at")
    private Instant checkedAt;

    public void apply(MetadataName row, Instant checkedAt) {
        this.code = row.code();
        this.nameKo = row.nameKo();
        this.source = row.source();
        this.l10nKey = row.l10nKey();
        this.checkedAt = checkedAt;
        applyDetails(row.details());
    }

    protected void applyDetails(MetadataName.Details details) {}
}
