package io.eranalytics.pipeline.collection.persistence.entity;

import io.eranalytics.pipeline.collection.model.DeathRow;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "participant_deaths")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeathEntity extends AssignedIdEntity<DeathId> {
    @EmbeddedId
    private DeathId id;

    @Column(name = "killer_type", columnDefinition = "text")
    private String killerType;

    @Column(name = "killer_participant_id")
    private Long killerParticipantId;

    @Column(name = "killer_character_num")
    private Integer killerCharacterNum;

    @Column(name = "killer_name", columnDefinition = "text")
    private String killerName;

    @Column(name = "killer_weapon", columnDefinition = "text")
    private String killerWeapon;

    @Column(name = "cause_of_death", columnDefinition = "text")
    private String causeOfDeath;

    @Column(name = "place_of_death")
    private Integer placeOfDeath;

    public DeathEntity(long participantId, DeathRow row, Long killerId) {
        this.id = new DeathId(participantId, row.deathSequence());
        this.killerType = row.killerType();
        this.killerParticipantId = killerId;
        this.killerCharacterNum = row.killerCharacterNum();
        this.killerName = row.killerName();
        this.killerWeapon = row.killerWeapon();
        this.causeOfDeath = row.causeOfDeath();
        this.placeOfDeath = row.placeOfDeath();
    }

    public void updateFrom(DeathEntity row) {
        this.killerType = row.killerType;
        this.killerParticipantId = row.killerParticipantId;
        this.killerCharacterNum = row.killerCharacterNum;
        this.killerName = row.killerName;
        this.killerWeapon = row.killerWeapon;
        this.causeOfDeath = row.causeOfDeath;
        this.placeOfDeath = row.placeOfDeath;
    }
}
