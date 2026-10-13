package io.eranalytics.pipeline.webapi.config;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;

/** Web repositories never fall back to the collector's connection or transaction manager. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "er.web-api.enabled", havingValue = "true")
@EnableJpaRepositories(
        basePackages = "io.eranalytics.pipeline.webapi.repository",
        entityManagerFactoryRef = "webApiEntityManagerFactory",
        transactionManagerRef = "webApiTransactionManager")
public class WebApiPersistenceConfiguration {
    @Bean
    @ConfigurationProperties("er.web-api.datasource")
    public DataSourceProperties webApiDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @ConfigurationProperties("er.web-api.datasource.hikari")
    public HikariDataSource webApiDataSource(
            @Qualifier("webApiDataSourceProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean
    public LocalContainerEntityManagerFactoryBean webApiEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("webApiDataSource") DataSource dataSource) {
        return builder.dataSource(dataSource)
                .packages("io.eranalytics.pipeline.webapi.entity")
                .persistenceUnit("webapi")
                .properties(Map.of("hibernate.hbm2ddl.auto", "validate"))
                .build();
    }

    @Bean
    public PlatformTransactionManager webApiTransactionManager(
            @Qualifier("webApiEntityManagerFactory") EntityManagerFactory factory) {
        return new JpaTransactionManager(factory);
    }

    @Bean
    public JdbcTemplate webApiJdbcTemplate(@Qualifier("webApiDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
