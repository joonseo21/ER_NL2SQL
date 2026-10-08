package io.eranalytics.pipeline;

import io.eranalytics.pipeline.config.CollectionProperties;
import io.eranalytics.pipeline.config.ErApiProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({ErApiProperties.class, CollectionProperties.class})
public class PipelineApplication {
    public static void main(String[] args) {
        SpringApplication.run(PipelineApplication.class, args);
    }
}
