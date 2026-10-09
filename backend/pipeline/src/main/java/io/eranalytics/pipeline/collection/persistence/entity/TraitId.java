package io.eranalytics.pipeline.collection.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@EqualsAndHashCode
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TraitId implements Serializable {
    @Column(name = "participant_id")
    private Long participantId;

    @Column(name = "slot_type", columnDefinition = "text")
    private String slotType;

    @Column(name = "trait_code")
    private Integer traitCode;
}
