package io.eranalytics.pipeline;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("er.api")
public record ErApiProperties(
        String baseUrl,
        String key,
        int seasonId,
        int topRankerLimit,
        double requestsPerSecond
) {
}
