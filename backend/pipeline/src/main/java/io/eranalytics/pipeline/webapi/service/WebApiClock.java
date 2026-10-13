package io.eranalytics.pipeline.webapi.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** A web-specific type so collector Clock injection remains unambiguous. */
public class WebApiClock {
    private final Clock clock;

    public WebApiClock(Clock clock) {
        this.clock = clock;
    }

    public Instant instant() {
        return clock.instant();
    }

    public LocalDate today() {
        return LocalDate.ofInstant(instant(), ZoneId.of("Asia/Seoul"));
    }
}
