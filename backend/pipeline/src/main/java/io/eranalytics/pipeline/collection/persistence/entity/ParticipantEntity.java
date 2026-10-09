package io.eranalytics.pipeline.collection.persistence.entity;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.eranalytics.pipeline.collection.model.MappedParticipant;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "participants")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ParticipantEntity {
    // V3's UNIQUE identity is Hibernate's ID; the DB's (game_id,nickname) PK stays intact.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "participant_id")
    private Long participantId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "version_major")
    private Integer versionMajor;

    @Column(name = "version_minor")
    private Integer versionMinor;

    @Column(name = "tier", columnDefinition = "text")
    private String tier;

    @Column(name = "tier_division")
    private Short tierDivision;

    @Column(name = "is_ranker", nullable = false)
    private boolean ranker;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw", nullable = false, columnDefinition = "jsonb")
    private ObjectNode raw;

    @Embedded
    private ParticipantCoreFields core;

    @Embedded
    private CombatFields combat;

    @Embedded
    private CharacterStatsFields stats;

    @Embedded
    private CreditFields credits;

    @Embedded
    private CraftFields crafting;

    @Embedded
    private ActivityFields activity;

    @Embedded
    private LoadoutFields loadout;

    public ParticipantEntity(MappedParticipant mapped, long userId, TierEntity boundary) {
        apply(mapped, userId, boundary);
    }

    public void apply(MappedParticipant mapped, long userId, TierEntity boundary) {
        var data = mapped.data();
        this.userId = userId;
        this.versionMajor = data.versionMajor();
        this.versionMinor = data.versionMinor();
        this.tier = boundary == null ? null : boundary.getId().getTier();
        this.tierDivision = boundary == null ? null : boundary.getId().getDivision();
        this.raw = mapped.remainingRaw().deepCopy();
        this.core = new ParticipantCoreFields(data.core());
        this.combat = new CombatFields(data.combat());
        this.stats = new CharacterStatsFields(data.stats());
        this.credits = new CreditFields(data.credits());
        this.crafting = new CraftFields(data.crafting());
        this.activity = new ActivityFields(data.activity());
        this.loadout = new LoadoutFields(data.loadout());
    }
}
