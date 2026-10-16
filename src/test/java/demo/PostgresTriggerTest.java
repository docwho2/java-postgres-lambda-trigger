package demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Checks validated trigger dispatch and acknowledgement only after successful processing. */
class PostgresTriggerTest {
    /** Shared test JSON parser. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Creates an offline trigger-contract test instance. */
    PostgresTriggerTest() {
    }

    /**
     * Checks required and unused row images for all three supported operations.
     *
     * @param operation supported uppercase PostgreSQL operation
     * @throws IOException if test JSON cannot be parsed or the response cannot be written
     */
    @ParameterizedTest
    @ValueSource(strings = {"INSERT", "UPDATE", "DELETE"})
    void acknowledgesOnlyAfterDispatch(String operation) throws IOException {
        String oldRecord = operation.equals("INSERT") ? "null" : "{\"id\":7}";
        String newRecord = operation.equals("DELETE") ? "null" : "{\"id\":7}";
        var payload = "{\"TG_OP\":\"" + operation + "\",\"TG_TABLE_NAME\":\"address\",\"old\":"
                + oldRecord + ",\"new\":" + newRecord + "}";
        var trigger = new RecordingTrigger();
        var output = invoke(trigger, payload);
        assertEquals("OK", MAPPER.readTree(output.toByteArray()).path("status").asText());
        assertEquals(1, trigger.changes.size());
        assertEquals(operation, trigger.changes.getFirst().operation().name());
        assertEquals("address", trigger.changes.getFirst().tableName());
        assertEquals(oldRecord, trigger.changes.getFirst().oldRecord().toString());
        assertEquals(newRecord, trigger.changes.getFirst().newRecord().toString());
    }

    /**
     * Checks that malformed envelopes never reach a side-effect handler.
     *
     * @param payload invalid event JSON, including nested metadata and wrong row-image types
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "", "null", "[]", "{}", "{",
        "{\"TG_OP\":\"INSERT\",\"new\":{\"TG_TABLE_NAME\":\"address\"}}",
        "{\"TG_TABLE_NAME\":\"address\",\"new\":{\"TG_OP\":\"INSERT\"}}",
        "{\"TG_OP\":\"TRUNCATE\",\"TG_TABLE_NAME\":\"address\"}",
        "{\"TG_OP\":\"INSERT\",\"TG_TABLE_NAME\":\" \n\",\"new\":{}}",
        "{\"TG_OP\":\"INSERT\",\"TG_TABLE_NAME\":4,\"new\":{}}",
        "{\"TG_OP\":\"INSERT\",\"TG_TABLE_NAME\":\"address\",\"new\":null}",
        "{\"TG_OP\":\"UPDATE\",\"TG_TABLE_NAME\":\"address\",\"new\":{}}",
        "{\"TG_OP\":\"DELETE\",\"TG_TABLE_NAME\":\"address\",\"old\":[]}",
        "{\"TG_OP\":\"INSERT\",\"TG_TABLE_NAME\":\"address\",\"old\":{},\"new\":{}}"
    })
    void rejectsMalformedPayloadsWithoutAcknowledging(String payload) {
        var trigger = new RecordingTrigger();
        var output = new ByteArrayOutputStream();
        assertThrows(Exception.class, () -> trigger.handleRequest(
                new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8)), output, null));
        assertEquals(0, output.size());
        assertTrue(trigger.changes.isEmpty());
    }

    /** Verifies that the original processing failure escapes without writing a false OK response. */
    @Test
    void processingFailurePropagatesToLambda() {
        var trigger = new RecordingTrigger();
        trigger.failure = new IllegalStateException("test write failed");
        var output = new ByteArrayOutputStream();
        var thrown = assertThrows(IllegalStateException.class, () -> trigger.handleRequest(
                new ByteArrayInputStream(validInsert().getBytes(StandardCharsets.UTF_8)), output, null));
        assertSame(trigger.failure, thrown);
        assertEquals(0, output.size());
    }

    /**
     * Confirms an unused absent INSERT image is normalized to JSON null for downstream handlers.
     *
     * @throws IOException if the test payload cannot be parsed
     */
    @Test
    void normalizesAbsentUnusedRowImage() throws IOException {
        var change = PostgresChange.fromJson(MAPPER.readTree(validInsert()));
        assertTrue(change.oldRecord().isNull());
    }

    /**
     * Runs a handler using in-memory request and response streams.
     *
     * @param trigger handler to exercise
     * @param payload JSON request body
     * @return collected response bytes
     * @throws IOException if handler stream processing fails
     */
    private static ByteArrayOutputStream invoke(PostgresAbstractTrigger trigger, String payload) throws IOException {
        var output = new ByteArrayOutputStream();
        trigger.handleRequest(new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8)), output, null);
        return output;
    }

    /**
     * Builds the smallest valid INSERT event used by the trigger and queue tests.
     *
     * @return a valid event with no unused old image
     */
    static String validInsert() {
        return "{\"TG_OP\":\"INSERT\",\"TG_TABLE_NAME\":\"address\",\"new\":{\"id\":7}}";
    }

    /** Captures dispatched events or throws a configured failure without touching a database. */
    private static final class RecordingTrigger extends PostgresAbstractTrigger {
        /** Captured successful deliveries. */
        private final List<PostgresChange> changes = new ArrayList<>();
        /** Optional processing failure. */
        private RuntimeException failure;

        /** Creates a recorder with no configured failure. */
        private RecordingTrigger() {
        }

        /**
         * Records a decoded event or propagates the configured persistence failure.
         *
         * @param operation decoded row operation
         * @param tableName source table name
         * @param oldRecord pre-change image
         * @param newRecord post-change image
         */
        @Override
        protected void processEvent(TG_OP operation, String tableName, JsonNode oldRecord, JsonNode newRecord) {
            if (failure != null) {
                throw failure;
            }
            changes.add(new PostgresChange(operation, tableName, oldRecord, newRecord));
        }
    }
}
