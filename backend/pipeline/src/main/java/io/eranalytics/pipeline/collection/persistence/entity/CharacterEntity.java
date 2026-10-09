package io.eranalytics.pipeline.collection.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

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
}
