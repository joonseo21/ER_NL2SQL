package io.eranalytics.pipeline.verification;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

final class Stage5DatabaseGuard {
    static final String OWNER = "stage5_test";
    static final Path ROOT = Path.of("../..").toAbsolutePath().normalize();

    static String copyUrl() {
        if (!"true".equals(System.getenv("STAGE5_LIVE_API"))) {
            throw new IllegalStateException("Explicit STAGE5_LIVE_API=true is required");
        }
        String url = System.getenv("STAGE5_TEST_DATABASE_URL");
        if (url == null || !url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):15445/er_stage5_copy")) {
            throw new IllegalStateException("Use the dedicated local stage5 copy on port 15445");
        }
        return url;
    }

    static String referenceUrl() { return copyUrl().replace("/er_stage5_copy", "/er_stage5_reference"); }

    static Connection owner(String url, String database, String marker) throws Exception {
        Connection connection = DriverManager.getConnection(url, OWNER, "");
        try (var sql = connection.createStatement(); var result = sql.executeQuery("""
                SELECT current_database(), current_user,
                       shobj_description(oid, 'pg_database') FROM pg_database WHERE datname=current_database()
                """)) {
            result.next();
            if (!database.equals(result.getString(1)) || !OWNER.equals(result.getString(2))
                    || !marker.equals(result.getString(3))) {
                throw new IllegalStateException("Dedicated verification database identity/marker required");
            }
            return connection;
        } catch (Exception error) {
            connection.close();
            throw error;
        }
    }

    static Connection copyOwner() throws Exception {
        return owner(copyUrl(), "er_stage5_copy", "ER_ANALYTICS_STAGE5_COPY");
    }

    static Connection referenceOwner() throws Exception {
        return owner(referenceUrl(), "er_stage5_reference", "ER_ANALYTICS_STAGE5_REFERENCE");
    }

    static Path captures() throws Exception {
        String configured = System.getenv("STAGE5_CAPTURE_DIRECTORY");
        if (configured == null || !Path.of(configured).isAbsolute()) {
            throw new IllegalStateException("An absolute, private capture directory outside the repository is required");
        }
        Path directory = Files.createDirectories(Path.of(configured)).toRealPath();
        if (directory.startsWith(ROOT.toRealPath())) {
            throw new IllegalStateException("Never save real API inputs inside the repository");
        }
        return directory;
    }

    private Stage5DatabaseGuard() {}
}
