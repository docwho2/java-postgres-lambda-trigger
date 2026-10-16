package demo;

import com.amazonaws.secretsmanager.sql.AWSSecretsManagerPostgreSQLDriver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import org.jooq.CloseableDSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.postgresql.PGConnection;

/**
 * Opens request-scoped PostgreSQL connections using the Secrets Manager JDBC driver.
 *
 * <p>Every {@link #getDSL()} result owns its connection and must be closed with
 * try-with-resources. No live database connection is retained during Lambda initialization
 * or between requests. Missing configuration and connection failures fail immediately.
 */
public class PostgresDataSource {

    /** Creates the stateless factory; all database entry points are static. */
    public PostgresDataSource() {
    }

    /**
     * Opens a closeable jOOQ context with credentials resolved from the configured RDS secret.
     *
     * @return a new PostgreSQL context whose connection is closed by {@code close()}
     * @throws IllegalStateException if a required environment variable or JDBC driver is missing
     * @throws DataAccessException if the JDBC connection cannot be established
     */
    public static CloseableDSLContext getDSL() {
        var properties = connectionProperties();
        var url = connectionUrl();
        initializeDriver();
        return openDSL(url, properties);
    }

    /**
     * Opens a PostgreSQL context for a JDBC URL, retaining ownership of the connection.
     * This package-private boundary also allows lifecycle tests with an offline JDBC driver.
     *
     * @param url JDBC connection URL
     * @param properties driver credentials and connection options
     * @return a context that closes the connection when its scope ends
     * @throws DataAccessException if connection creation fails
     */
    static CloseableDSLContext openDSL(String url, Properties properties) {
        // The URL overload owns its connection; DSL.using(Connection) does not.
        var dsl = DSL.using(url, properties);
        dsl.configuration().set(SQLDialect.POSTGRES);
        return dsl;
    }

    /**
     * Opens the PostgreSQL-specific view for callers that need driver extensions.
     *
     * <p>The caller must close the underlying connection by casting the returned PostgreSQL driver object
     * to {@link java.sql.Connection}, or use {@link #getDSL()} instead. This method is not
     * used by the demo handlers.
     *
     * @return the PostgreSQL connection implementing the driver-specific interface
     * @throws SQLException if opening or unwrapping the JDBC connection fails
     * @throws IllegalStateException if database configuration or the driver is missing
     */
    public static PGConnection getPGConnection() throws SQLException {
        var properties = connectionProperties();
        var url = connectionUrl();
        initializeDriver();
        var connection = DriverManager.getConnection(url, properties);
        try {
            return connection.unwrap(PGConnection.class);
        } catch (SQLException | RuntimeException failure) {
            // An unwrap failure must not orphan an otherwise successfully opened connection.
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    /**
     * Resolves the secret ARN without logging credentials or secret identifiers.
     *
     * @return JDBC properties using the secret ARN as the driver username
     * @throws IllegalStateException if the secret ARN environment variable is absent or blank
     */
    private static Properties connectionProperties() {
        var properties = new Properties();
        properties.put("user", requiredEnvironment("DB_MASTER_SECRET_ARN"));
        return properties;
    }

    /**
     * Builds the JDBC URL from the deployment's endpoint and database name.
     *
     * @return the Secrets Manager PostgreSQL JDBC URL
     * @throws IllegalStateException if either required environment variable is absent or blank
     */
    private static String connectionUrl() {
        return "jdbc-secretsmanager:postgresql://" + requiredEnvironment("DB_ENDPOINT")
                + "/" + requiredEnvironment("DB_NAME");
    }

    /**
     * Reads a required deployment setting and reports its name without exposing its value.
     *
     * @param name environment variable to read
     * @return the nonblank configured value
     * @throws IllegalStateException if the variable is absent or blank
     */
    private static String requiredEnvironment(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: " + name);
        }
        return value;
    }

    /**
     * Loads the Secrets Manager driver once through JVM class initialization, at first use.
     *
     * @throws IllegalStateException if the driver is missing from the deployment package
     */
    private static void initializeDriver() {
        System.setProperty("software.amazon.awssdk.http.service.impl",
                "software.amazon.awssdk.http.urlconnection.UrlConnectionSdkHttpService");
        try {
            Class.forName(AWSSecretsManagerPostgreSQLDriver.class.getName());
        } catch (ClassNotFoundException failure) {
            throw new IllegalStateException("Secrets Manager PostgreSQL driver is unavailable", failure);
        }
    }
}
