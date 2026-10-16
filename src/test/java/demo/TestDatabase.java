package demo;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.logging.Logger;
import org.jooq.CloseableDSLContext;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockResult;

/** Offline JDBC driver exercising the production connection factory and jOOQ execution path. */
final class TestDatabase implements Driver, AutoCloseable {
    /** Unique JDBC URL preventing one test's driver from intercepting another test's connection. */
    private final String url = "jdbc:review:" + UUID.randomUUID();
    /** Captured statements in execution order. */
    final List<String> statements = new ArrayList<>();
    /** Connections opened by the factory, inspected for proper closure. */
    final List<MockConnection> connections = new ArrayList<>();
    /** Optional connection acquisition failure for error-path tests. */
    SQLException connectionFailure;
    /** SQL behavior supplied by the test. */
    private final MockDataProvider provider;

    /**
     * Registers an isolated driver whose statements each affect one row.
     *
     * @throws SQLException if driver registration fails
     */
    TestDatabase() throws SQLException {
        this(context -> new MockResult[]{new MockResult(1)});
    }

    /**
     * Registers an isolated driver with configurable query behavior.
     *
     * @param provider result/error behavior for executed SQL
     * @throws SQLException if driver registration fails
     */
    TestDatabase(MockDataProvider provider) throws SQLException {
        this.provider = provider;
        DriverManager.registerDriver(this);
    }

    /**
     * Opens a context through the production factory without using AWS credentials or a network.
     *
     * @return a fresh connection-owning context
     */
    CloseableDSLContext open() {
        return PostgresDataSource.openDSL(url, new Properties());
    }

    /**
     * Creates a tracked mock connection for this fixture's URL.
     *
     * @param requestedUrl JDBC URL requested by DriverManager
     * @param properties unused connection properties
     * @return a mock connection, or null for a different fixture's URL
     * @throws SQLException when a simulated connection failure is configured
     */
    @Override
    public Connection connect(String requestedUrl, Properties properties) throws SQLException {
        if (!acceptsURL(requestedUrl)) {
            return null;
        }
        if (connectionFailure != null) {
            throw connectionFailure;
        }
        var connection = new MockConnection(context -> {
            statements.add(context.sql());
            return provider.execute(context);
        });
        connections.add(connection);
        return connection;
    }

    /**
     * Checks whether a URL belongs to this fixture.
     *
     * @param requestedUrl URL to inspect
     * @return true only for this fixture's exact URL
     */
    @Override
    public boolean acceptsURL(String requestedUrl) {
        return url.equals(requestedUrl);
    }

    /**
     * Reports that the mock driver needs no configuration properties.
     *
     * @param requestedUrl unused JDBC URL
     * @param properties unused property values
     * @return an empty property description array
     */
    @Override
    public DriverPropertyInfo[] getPropertyInfo(String requestedUrl, Properties properties) {
        return new DriverPropertyInfo[0];
    }

    /**
     * Reports the mock driver major version.
     *
     * @return the fixed major version
     */
    @Override
    public int getMajorVersion() {
        return 1;
    }

    /**
     * Reports the mock driver minor version.
     *
     * @return the fixed minor version
     */
    @Override
    public int getMinorVersion() {
        return 0;
    }

    /**
     * Describes whether the mock driver implements the complete JDBC contract.
     *
     * @return false because this driver only supports offline tests
     */
    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    /**
     * Exposes the fixture logger required by the JDBC Driver interface.
     *
     * @return the logger used for the offline JDBC fixture
     */
    @Override
    public Logger getParentLogger() {
        return Logger.getLogger(TestDatabase.class.getName());
    }

    /**
     * Removes this fixture from DriverManager after its assertions complete.
     *
     * @throws SQLException if driver deregistration fails
     */
    @Override
    public void close() throws SQLException {
        DriverManager.deregisterDriver(this);
    }
}
