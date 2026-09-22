package demo;

import com.amazonaws.secretsmanager.sql.AWSSecretsManagerPostgreSQLDriver;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.postgresql.PGConnection;

/**
 * Creates PostgreSQL connections using credentials resolved by the AWS Secrets Manager JDBC driver.
 *
 * <p>The SAM environment provides {@code DB_MASTER_SECRET_ARN}, {@code DB_ENDPOINT}, and
 * {@code DB_NAME}. Class initialization registers the Secrets Manager PostgreSQL driver and
 * selects the AWS URL-connection HTTP implementation. Each factory call opens a separate
 * connection; this class provides neither pooling nor automatic connection cleanup.
 *
 * @author jensen
 */
public class PostgresDataSource {

    /**
     * Creates an instance with no per-instance state; database access is provided by the static factories.
     */
    public PostgresDataSource() {
    }

    /**
     * Secret ARN passed as the JDBC user property so the driver can resolve database credentials.
     */
    static final String SECRET_ARN = System.getenv("DB_MASTER_SECRET_ARN");
    /** Database hostname supplied by the nested RDS stack through the SAM environment. */
    static final String DB_ENDPOINT = System.getenv("DB_ENDPOINT");
    /** Database name appended to the Secrets Manager JDBC URL. */
    static final String DB_NAME = System.getenv("DB_NAME");
    
    /** Shared JDBC connection properties containing the secret ARN in the user field. */
    static final Properties info;

    static {
        
        System.setProperty("software.amazon.awssdk.http.service.impl", 
                "software.amazon.awssdk.http.urlconnection.UrlConnectionSdkHttpService");

        System.out.println("Secrets ARN is " + SECRET_ARN);
        // Load the JDBC driver
        var driver = new AWSSecretsManagerPostgreSQLDriver();
        System.out.println("Driver initialzied " + driver);
        info = new Properties();
        info.put("user", SECRET_ARN);
    }

    /**
     * Opens a new connection to the configured database using the Secrets Manager driver.
     *
     * <p>SQL connection failures are printed to standard error and converted to {@code null}.
     * The factory does not retry, cache, or close a successfully opened connection.
     *
     * @return a newly opened JDBC connection, or {@code null} when the driver throws
     *         {@link SQLException}; the caller is responsible for the connection's lifetime
     */
    private static Connection getConnection() {
        try {
            // Establish the connection
            // Set the endpoint and port. You can also retrieve it from a key/value pair in the secret.
            final String URL = "jdbc-secretsmanager:postgresql://" + DB_ENDPOINT + "/" + DB_NAME;
            return DriverManager.getConnection(URL, info);
        } catch (SQLException se) {
            System.err.println(se.getMessage());
            return null;
        }
    }

    /**
     * Opens a connection and exposes PostgreSQL-specific driver operations through unwrapping.
     *
     * <p>Only the {@link PGConnection} view is returned; the underlying JDBC connection is
     * not closed by this method or retained by this class for later cleanup.
     *
     * @return the PostgreSQL-specific view of the newly opened JDBC connection
     * @throws SQLException if the opened connection cannot be unwrapped as a PostgreSQL connection
     * @throws NullPointerException if connection acquisition failed and returned {@code null}
     */
    public static PGConnection getPGConnection() throws SQLException {
        return (getConnection().unwrap(PGConnection.class));
    }

    /**
     * Creates a jOOQ context configured with the PostgreSQL dialect and a fresh JDBC connection.
     *
     * <p>The context wraps the connection supplied by {@link #getConnection()}; this factory
     * does not provide pooling, transaction boundaries, or automatic connection cleanup.
     * If acquisition returns {@code null}, the context has no usable connection for execution.
     *
     * @return a PostgreSQL jOOQ context for constructing and executing the demo's SQL statements
     */
    public static DSLContext getDSL() {
        return (DSL.using(getConnection(), SQLDialect.POSTGRES));
    }

}
