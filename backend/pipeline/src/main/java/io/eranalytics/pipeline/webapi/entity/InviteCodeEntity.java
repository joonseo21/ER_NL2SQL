package io.eranalytics.pipeline.webapi.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "invite_codes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InviteCodeEntity {
    @Id
    @Column(name = "code", columnDefinition = "text")
    private String code;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "memo", columnDefinition = "text")
    private String memo;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public InviteCodeEntity(String code, String memo, Instant createdAt) {
        this.code = code;
        this.memo = memo;
        this.createdAt = createdAt;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
