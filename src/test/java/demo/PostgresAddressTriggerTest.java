package demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import demo.PostgresAbstractTrigger.TG_OP;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.location.model.Place;
import software.amazon.awssdk.services.location.model.PlaceGeometry;
import software.amazon.awssdk.services.location.model.SearchForTextResult;
import software.amazon.awssdk.services.location.model.SearchPlaceIndexForTextResponse;
import static org.junit.jupiter.api.Assertions.*;

/** Tests address-change selection, geocoding writes, bounded retries, and interruption offline. */
class PostgresAddressTriggerTest {
    /** Reusable JSON codec for address fixtures. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Creates a test instance without initializing the production Location client. */
    PostgresAddressTriggerTest() {
    }

    /**
     * Confirms every address component can trigger a fresh lookup, including city-only changes.
     *
     * @param field address component to change
     * @throws IOException if fixture JSON cannot be parsed
     */
    @ParameterizedTest
    @ValueSource(strings = {"address_1", "city", "district", "postal_code"})
    void geocodesChangesToEveryAddressComponent(String field) throws IOException {
        var searches = new ArrayList<String>();
        var handler = new PostgresAddressTrigger(text -> {
            searches.add(text);
            return SearchPlaceIndexForTextResponse.builder().build();
        }, () -> { throw new AssertionError("No match must not open a database connection"); }, delay -> fail("Unexpected delay"));
        var before = address();
        var after = before.deepCopy().put(field, "Changed");
        handler.processEvent(TG_OP.UPDATE, "address", before, after);
        assertEquals(1, searches.size());
        assertTrue(searches.getFirst().contains("Changed"));
    }

    /**
     * Verifies DELETE, unchanged address fields, and unrelated notes do not call external services.
     *
     * @throws IOException if fixture JSON cannot be parsed
     */
    @Test
    void skipsDeletesAndNonAddressChanges() throws IOException {
        var handler = new PostgresAddressTrigger(text -> { throw new AssertionError("Unexpected lookup"); },
                () -> { throw new AssertionError("Unexpected connection"); }, delay -> fail("Unexpected delay"));
        var before = address();
        handler.processEvent(TG_OP.DELETE, "address", before, MAPPER.nullNode());
        handler.processEvent(TG_OP.UPDATE, "address", before, before.deepCopy().put("address_notes", "New note"));
        assertThrows(IllegalArgumentException.class, () -> handler.processEvent(TG_OP.INSERT, "other", MAPPER.nullNode(), before));
    }

    /**
     * Verifies missing optional postal code is accepted and a matching result closes its connection.
     *
     * @throws IOException if fixture JSON cannot be parsed
     * @throws SQLException if the offline JDBC fixture cannot be registered or inspected
     */
    @Test
    void persistsMatchAndClosesConnection() throws IOException, SQLException {
        var lookups = new ArrayList<String>();
        try (var database = new TestDatabase()) {
            var handler = new PostgresAddressTrigger(text -> {
                lookups.add(text);
                return match();
            }, database::open, delay -> fail("A successful first update must not sleep"));
            var row = address();
            row.remove("postal_code");
            handler.processEvent(TG_OP.INSERT, "address", MAPPER.nullNode(), row);
            assertEquals(List.of("1 Main St, Minneapolis, MN"), lookups);
            assertEquals(1, database.statements.size());
            assertTrue(database.statements.getFirst().contains("requires_geo_coding"));
            assertTrue(database.statements.getFirst().contains("geo_longitude"));
            assertTrue(database.connections.getFirst().isClosed());
        }
    }

    /**
     * Verifies an invisible source row fails after bounded attempts and releases its connection.
     *
     * @throws IOException if fixture JSON cannot be parsed
     * @throws SQLException if the offline JDBC fixture cannot be registered or inspected
     */
    @Test
    void invisibleRowExhaustsRetriesAndClosesConnection() throws IOException, SQLException {
        try (var database = new TestDatabase(context -> new MockResult[]{new MockResult(0)})) {
            var delays = new ArrayList<Long>();
            var handler = new PostgresAddressTrigger(text -> match(), database::open, delays::add);
            var row = address();
            assertThrows(IllegalStateException.class, () -> handler.processEvent(TG_OP.INSERT, "address", MAPPER.nullNode(), row));
            assertEquals(10, database.statements.size());
            assertEquals(List.of(100L, 200L, 300L, 400L, 500L, 600L, 700L, 800L, 900L), delays);
            assertTrue(database.connections.getFirst().isClosed());
        }
    }

    /**
     * Checks that a failed SQL write propagates immediately and still releases its connection.
     *
     * @throws IOException if fixture JSON cannot be parsed
     * @throws SQLException if the offline JDBC fixture cannot be registered or inspected
     */
    @Test
    void sqlFailureIsNotRetriedInsideTheInvocation() throws IOException, SQLException {
        try (var database = new TestDatabase(context -> { throw new SQLException("test write failure"); })) {
            var handler = new PostgresAddressTrigger(text -> match(), database::open,
                    delay -> fail("Only zero-row results are retried locally"));
            var row = address();
            assertThrows(org.jooq.exception.DataAccessException.class,
                    () -> handler.processEvent(TG_OP.INSERT, "address", MAPPER.nullNode(), row));
            assertEquals(1, database.statements.size());
            assertTrue(database.connections.getFirst().isClosed());
        }
    }

    /**
     * Checks a failed geocoding lookup leaves persistence untouched and fails the invocation.
     *
     * @throws IOException if fixture JSON cannot be parsed
     */
    @Test
    void lookupFailureDoesNotOpenDatabase() throws IOException {
        var failure = new IllegalStateException("test lookup failure");
        var handler = new PostgresAddressTrigger(text -> { throw failure; },
                () -> { throw new AssertionError("Unexpected connection"); }, delay -> {});
        var row = address();
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> handler.processEvent(TG_OP.INSERT, "address", MAPPER.nullNode(), row)));
    }

    /** Verifies retry stops as soon as a previously invisible row becomes visible. */
    @Test
    void retrySucceedsWithoutExtraSleep() {
        var attempts = new AtomicInteger();
        var delays = new ArrayList<Long>();
        PostgresAddressTrigger.retryUpdate(() -> attempts.incrementAndGet() == 3 ? 1 : 0, delays::add);
        assertEquals(3, attempts.get());
        assertEquals(List.of(100L, 200L), delays);
    }

    /** Checks both incoming interruption and interruption while sleeping stop retries and preserve the flag. */
    @Test
    void interruptionStopsWorkAndPreservesInterruptFlag() {
        try {
            Thread.currentThread().interrupt();
            assertThrows(IllegalStateException.class, () -> PostgresAddressTrigger.retryUpdate(
                    () -> { throw new AssertionError("Interrupted request must not execute SQL"); }, delay -> {}));
            assertTrue(Thread.interrupted());
            var attempts = new AtomicInteger();
            assertThrows(IllegalStateException.class, () -> PostgresAddressTrigger.retryUpdate(
                    () -> { attempts.incrementAndGet(); return 0; }, delay -> { throw new InterruptedException("test"); }));
            assertEquals(1, attempts.get());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    /**
     * Rejects invalid required fields before any external lookup or database call.
     *
     * @param field required address property to remove
     * @throws IOException if fixture JSON cannot be parsed
     */
    @ParameterizedTest
    @ValueSource(strings = {"id", "address_1", "city", "district"})
    void validatesRequiredAddressFields(String field) throws IOException {
        var handler = new PostgresAddressTrigger(text -> { throw new AssertionError("Unexpected lookup"); },
                () -> { throw new AssertionError("Unexpected connection"); }, delay -> {});
        var row = address();
        row.remove(field);
        assertThrows(IllegalArgumentException.class, () -> handler.processEvent(TG_OP.INSERT, "address", MAPPER.nullNode(), row));
    }

    /**
     * Builds a complete address fixture.
     *
     * @return mutable address row for individual test variations
     * @throws IOException if fixture JSON cannot be parsed
     */
    private static ObjectNode address() throws IOException {
        return (ObjectNode) MAPPER.readTree("""
                {"id":7,"address_1":"1 Main St","city":"Minneapolis","district":"MN","postal_code":"55401"}
                """);
    }

    /**
     * Builds a successful Location response for offline persistence tests.
     *
     * @return a deterministic result with longitude before latitude
     */
    private static SearchPlaceIndexForTextResponse match() {
        return SearchPlaceIndexForTextResponse.builder().results(SearchForTextResult.builder()
                .place(Place.builder().label("1 Main St, Minneapolis, MN")
                        .geometry(PlaceGeometry.builder().point(-93.2, 44.9).build()).build()).build()).build();
    }
}
