package io.eranalytics.pipeline;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("er.collection")
public record CollectionProperties(
        boolean enabled,
        boolean continuous,
        Duration interval
) {
}
