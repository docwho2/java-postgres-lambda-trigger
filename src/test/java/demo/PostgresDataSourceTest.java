package demo;

import java.sql.SQLException;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Checks connection ownership and failure propagation without AWS or PostgreSQL access. */
class PostgresDataSourceTest {
    /** Creates an offline connection-lifecycle test instance. */
    PostgresDataSourceTest() {
    }

    /**
     * Checks that separate requests receive separate connections and close them on success.
     *
     * @throws SQLException if the mock JDBC fixture cannot be registered or inspected
     */
    @Test
    void closesEveryRequestConnection() throws SQLException {
        try (var database = new TestDatabase()) {
            for (int i = 0; i < 2; i++) {
                try (var dsl = database.open()) {
                    assertEquals(SQLDialect.POSTGRES, dsl.dialect());
                    dsl.execute("select 1");
                    assertFalse(database.connections.get(i).isClosed());
                }
                assertTrue(database.connections.get(i).isClosed());
            }
            assertEquals(2, database.connections.size());
            assertNotSame(database.connections.get(0), database.connections.get(1));
        }
    }

    /**
     * Checks that a query exception still closes the successfully opened JDBC connection.
     *
     * @throws SQLException if the offline driver cannot be registered or inspected
     */
    @Test
    void closesConnectionWhenQueryFails() throws SQLException {
        try (var database = new TestDatabase(context -> { throw new SQLException("test write failed"); })) {
            assertThrows(DataAccessException.class, () -> {
                try (var dsl = database.open()) {
                    dsl.execute("select 1");
                }
            });
            assertTrue(database.connections.getFirst().isClosed());
        }
    }

    /**
     * Checks that connection errors retain their cause instead of returning a null context.
     *
     * @throws SQLException if the mock driver cannot be registered or deregistered
     */
    @Test
    void connectionFailureIsNotConvertedToNull() throws SQLException {
        try (var database = new TestDatabase()) {
            database.connectionFailure = new SQLException("test connection failure");
            var failure = assertThrows(DataAccessException.class, database::open);
            assertSame(database.connectionFailure, failure.getCause());
            assertTrue(database.connections.isEmpty());
        }
    }
}
