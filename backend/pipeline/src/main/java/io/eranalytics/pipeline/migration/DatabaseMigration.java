package io.eranalytics.pipeline.migration;

import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;

/** Explicit maintenance entry point. Does not start Spring or call the game API. */
public final class DatabaseMigration {
    private DatabaseMigration() {}

    public static void main(String[] args) {
        if (args.length == 0 || !java.util.Set.of("info", "validate", "migrate", "baseline").contains(args[0])
                || args.length != ("baseline".equals(args[0]) ? 2 : 1)) {
            throw new IllegalArgumentException("Use info, validate, migrate, or baseline <verified-version>");
        }
        Map<String, String> environment = System.getenv();
        FluentConfiguration configuration = configure(
                required(environment, "MIGRATION_DATABASE_URL"),
                required(environment, "MIGRATION_DATABASE_USER"),
                environment.getOrDefault("MIGRATION_DATABASE_PASSWORD", ""));
        if ("baseline".equals(args[0])) {
            if (!args[1].matches("[1-9][0-9]*(\\.[0-9]+)*")) {
                throw new IllegalArgumentException("Baseline version must be an explicitly verified positive version");
            }
            configuration.baselineVersion(args[1]).baselineDescription("Existing schema verified by operator");
        }
        Flyway flyway = configuration.load();
        switch (args[0]) {
            case "info" -> {
                for (var migration : flyway.info().all()) {
                    System.out.printf("%s | %s | %s%n", migration.getVersion(),
                            migration.getDescription(), migration.getState());
                }
            }
            case "validate" -> flyway.validate();
            case "migrate" -> flyway.migrate();
            case "baseline" -> flyway.baseline();
            default -> throw new IllegalStateException("Unsupported migration command");
        }
    }

    public static FluentConfiguration configure(String url, String user, String password) {
        // Keep credentials out of URLs, which database tools may log.
        if (!url.startsWith("jdbc:postgresql://")
                || url.matches("(?i).*[?&](password|user)=[^&]*.*") || url.contains("@")) {
            throw new IllegalArgumentException("Use a PostgreSQL JDBC URL with credentials in separate environment variables");
        }
        return Flyway.configure().dataSource(url, user, password)
                .locations("classpath:db/migration").defaultSchema("public").schemas("public")
                .baselineOnMigrate(false).cleanDisabled(true).validateOnMigrate(true)
                .validateMigrationNaming(true).failOnMissingLocations(true).outOfOrder(false);
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
