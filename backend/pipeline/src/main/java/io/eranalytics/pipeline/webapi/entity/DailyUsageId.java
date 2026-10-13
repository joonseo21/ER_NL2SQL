package io.eranalytics.pipeline.webapi.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.time.LocalDate;
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
public class DailyUsageId implements Serializable {
    @Column(name = "usage_date", nullable = false)
    private LocalDate usageDate;

    @Column(name = "scope_key", columnDefinition = "text", nullable = false)
    private String scopeKey;
}
