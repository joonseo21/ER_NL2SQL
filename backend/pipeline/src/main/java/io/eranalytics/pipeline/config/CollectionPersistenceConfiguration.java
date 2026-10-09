package io.eranalytics.pipeline.config;

import io.eranalytics.pipeline.collection.BattleUserResultMapper;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CollectionPersistenceConfiguration {
    @Bean
    public BattleUserResultMapper battleUserResultMapper() {
        return new BattleUserResultMapper();
    }

    @Bean
    public Clock collectionClock() {
        return Clock.systemUTC();
    }
}
