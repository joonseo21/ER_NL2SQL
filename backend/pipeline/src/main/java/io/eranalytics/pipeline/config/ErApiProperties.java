package io.eranalytics.pipeline.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("er.api")
public record ErApiProperties(
        String baseUrl,
        String key,
        int seasonId,
        double requestsPerSecond
) {
}
