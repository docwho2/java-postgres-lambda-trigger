package demo;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.lambda.powertools.cloudformation.Response;
import static org.junit.jupiter.api.Assertions.*;

/** Checks initialization ordering, real failure responses, packaged resources, and connection closure. */
class CloudFormationCustomResourceTest {
    /** Creates an offline CloudFormation callback test instance. */
    CloudFormationCustomResourceTest() {
    }

    /**
     * Checks script dependency order and that seed insertion follows all five successful scripts.
     *
     * @throws SQLException if the mock driver cannot be created or inspected
     */
    @Test
    void scriptsRunInOrderBeforeSeedingAndConnectionCloses() throws SQLException {
        try (var database = new TestDatabase()) {
            var loaded = new ArrayList<String>();
            var handler = new CloudFormationCustomResource(database::open, name -> {
                loaded.add(name);
                assertTrue(database.statements.isEmpty(), "Load every script before changing the database");
                return "-- " + name;
            });
            var response = handler.create(null, null);
            assertEquals(Response.Status.SUCCESS, response.getStatus());
            assertEquals(List.of("enableLambdaExtension.sql", "LambdaTriggerFuction.sql", "createAddressTable.sql",
                    "createAuditLogTable.sql", "createAddressTrigger.sql"), loaded);
            assertEquals(6, database.statements.size());
            for (int index = 0; index < loaded.size(); index++) {
                assertEquals("-- " + loaded.get(index), database.statements.get(index));
            }
            assertTrue(database.statements.getLast().startsWith("insert into"));
            assertTrue(database.connections.getFirst().isClosed());
        }
    }

    /**
     * Checks that each failing statement aborts initialization without a misleading success response.
     *
     * @param failedStatement one-based failing script or seed statement index
     * @throws SQLException if the mock driver cannot be created or inspected
     */
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6})
    void sqlFailureStopsFurtherStatements(int failedStatement) throws SQLException {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        try (var database = new TestDatabase(context -> {
            if (calls.incrementAndGet() == failedStatement) {
                throw new SQLException("test sensitive SQL error");
            }
            return new org.jooq.tools.jdbc.MockResult[]{new org.jooq.tools.jdbc.MockResult(1)};
        })) {
            var response = new CloudFormationCustomResource(database::open, name -> "-- " + name).create(null, null);
            assertEquals(Response.Status.FAILED, response.getStatus());
            assertEquals("db_sql_initialize", response.getPhysicalResourceId());
            assertFalse(response.getReason().contains("sensitive"));
            assertEquals(failedStatement, calls.get());
            assertTrue(database.connections.getFirst().isClosed());
        }
    }

    /** Checks that missing resources fail before connection creation and UPDATE/DELETE do no setup work. */
    @Test
    void missingScriptFailsBeforeDatabaseAccess() {
        var handler = new CloudFormationCustomResource(() -> { throw new AssertionError("Unexpected connection"); },
                name -> { throw new IllegalStateException("Missing resource"); });
        assertEquals(Response.Status.FAILED, handler.create(null, null).getStatus());
        assertEquals(Response.Status.SUCCESS, handler.update(null, null).getStatus());
        assertEquals(Response.Status.SUCCESS, handler.delete(null, null).getStatus());
    }

    /** Checks that connection acquisition failures also produce the required CloudFormation failure response. */
    @Test
    void connectionFailureReturnsFailed() {
        var handler = new CloudFormationCustomResource(() -> { throw new IllegalStateException("Connection failed"); },
                name -> "-- " + name);
        assertEquals(Response.Status.FAILED, handler.create(null, null).getStatus());
    }

    /**
     * Checks classpath loading independently of Lambda's filesystem layout.
     *
     * @param filename SQL asset required for initialization
     */
    @ParameterizedTest
    @ValueSource(strings = {"enableLambdaExtension.sql", "LambdaTriggerFuction.sql", "createAddressTable.sql",
        "createAuditLogTable.sql", "createAddressTrigger.sql"})
    void readsPackagedScripts(String filename) {
        assertFalse(CloudFormationCustomResource.readScript(filename).isBlank());
    }

    /** Checks a missing SQL asset produces an actionable initialization failure. */
    @Test
    void missingPackagedScriptIsAnError() {
        assertThrows(IllegalStateException.class, () -> CloudFormationCustomResource.readScript("missing.sql"));
    }
}
