package demo;

import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises full-batch processing, failed-message retry reporting, and warm request isolation. */
class PostgresAuditLogTriggerSQSTest {
    /** Creates an offline SQS consumer test instance. */
    PostgresAuditLogTriggerSQSTest() {
    }

    /** Verifies that every record is handled and a failed middle record does not stop the batch. */
    @Test
    void retriesOnlyFailedMessagesAndProcessesTheWholeBatch() {
        var calls = new AtomicInteger();
        var handler = new PostgresAuditLogTriggerSQS(change -> {
            if (calls.incrementAndGet() == 2) {
                throw new IllegalStateException("test database failure");
            }
        });
        var response = handler.handleRequest(batch(message("first", PostgresTriggerTest.validInsert()),
                message("second", PostgresTriggerTest.validInsert()),
                message("third", PostgresTriggerTest.validInsert())), null);
        assertEquals(3, calls.get());
        assertEquals(List.of("second"), response.getBatchItemFailures().stream()
                .map(failure -> failure.getItemIdentifier()).toList());
        assertTrue(handler.handleRequest(batch(message("next", PostgresTriggerTest.validInsert())), null)
                .getBatchItemFailures().isEmpty());
    }

    /**
     * Checks that empty or malformed bodies are retryable failures and never reach persistence.
     *
     * @param body missing, empty, malformed, or incomplete trigger JSON
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "null", "{}", "{", "[]"})
    void invalidBodiesAreFailures(String body) {
        var handler = new PostgresAuditLogTriggerSQS(change -> fail("Invalid body reached persistence"));
        var response = handler.handleRequest(batch(message("bad", body)), null);
        assertEquals("bad", response.getBatchItemFailures().getFirst().getItemIdentifier());
    }

    /** Verifies all message IDs are checked before the first database side effect. */
    @Test
    void missingMessageIdFailsBeforeAnyWrites() {
        var handler = new PostgresAuditLogTriggerSQS(change -> fail("Must prevalidate the batch"));
        assertThrows(IllegalArgumentException.class, () -> handler.handleRequest(batch(
                message("good", PostgresTriggerTest.validInsert()),
                message(null, PostgresTriggerTest.validInsert())), null));
        assertThrows(IllegalArgumentException.class, () -> handler.handleRequest(null, null));
        assertThrows(IllegalArgumentException.class, () -> handler.handleRequest(new SQSEvent(), null));
        assertThrows(IllegalArgumentException.class, () -> handler.handleRequest(batch((SQSEvent.SQSMessage) null), null));
    }

    /** Confirms that an empty batch and consumer construction require no credentials or database access. */
    @Test
    void emptyBatchDoesNotInitializeDatabase() {
        assertTrue(new PostgresAuditLogTriggerSQS().handleRequest(batch(), null).getBatchItemFailures().isEmpty());
    }

    /**
     * Builds a queue message for offline delivery.
     *
     * @param id message identifier, including null for negative cases
     * @param body trigger JSON or an invalid test value
     * @return a new SQS message
     */
    private static SQSEvent.SQSMessage message(String id, String body) {
        var message = new SQSEvent.SQSMessage();
        message.setMessageId(id);
        message.setBody(body);
        return message;
    }

    /**
     * Builds a batch preserving order and allowing null entries for validation tests.
     *
     * @param messages ordered queue records
     * @return an event containing those records
     */
    private static SQSEvent batch(SQSEvent.SQSMessage... messages) {
        var event = new SQSEvent();
        event.setRecords(new ArrayList<>(Arrays.asList(messages)));
        return event;
    }
}
