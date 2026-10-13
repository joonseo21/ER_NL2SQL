package io.eranalytics.pipeline.webapi.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** The caller supplies the Asia/Seoul date; null inviteCode denotes the global counter. */
@Entity
@Table(name = "daily_usage")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DailyUsageEntity {
    @EmbeddedId private DailyUsageId id;

    @Column(name = "invite_code", columnDefinition = "text")
    private String inviteCode;

    @Column(name = "request_count", nullable = false)
    private int requestCount;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public DailyUsageEntity(LocalDate usageDate, String inviteCode) {
        this.id =
                new DailyUsageId(usageDate, inviteCode == null ? "GLOBAL" : "INVITE:" + inviteCode);
        this.inviteCode = inviteCode;
    }

    public void increment() {
        requestCount = Math.incrementExact(requestCount);
    }
}
