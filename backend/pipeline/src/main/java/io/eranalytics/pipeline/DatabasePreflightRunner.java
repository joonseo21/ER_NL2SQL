package io.eranalytics.pipeline;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "er.collection.enabled", havingValue = "true")
final class DatabasePreflightRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DatabasePreflightRunner.class);

    private final DataSource dataSource;
    private final ConfigurableEnvironment environment;

    DatabasePreflightRunner(DataSource dataSource, ConfigurableEnvironment environment) {
        this.dataSource = dataSource;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        String password = environment.getProperty("spring.datasource.password", "");
        List<String> passwordSources = findPropertySources("COLLECTOR_PASSWORD");
        log.info("Collector DB preflight: url={}, username={}, passwordConfigured={}, passwordLength={}, passwordSources={}",
                environment.getProperty("spring.datasource.url"),
                environment.getProperty("spring.datasource.username"),
                !password.isBlank(), password.length(), passwordSources);

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT current_user")) {
            result.next();
            log.info("Collector DB preflight succeeded: current_user={}", result.getString(1));
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Collector DB preflight failed. Check IntelliJ environment overrides and the root .env file.",
                    exception);
        }
    }

    private List<String> findPropertySources(String propertyName) {
        List<String> sources = new ArrayList<>();
        for (PropertySource<?> propertySource : environment.getPropertySources()) {
            if (propertySource.getProperty(propertyName) != null) {
                sources.add(propertySource.getName());
            }
        }
        return sources;
    }
}
