package demo;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Objects;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jooq.JSONB;
import org.jooq.impl.DSL;

/**
 * Persists SQS-delivered audit events and reports only failed records for redelivery.
 *
 * <p>The SAM event source must enable {@code ReportBatchItemFailures}. Each successful
 * insert owns and closes its database connection. Delivery remains at-least-once; no
 * application-level deduplication is performed. No database access occurs at initialization.
 */
public class PostgresAuditLogTriggerSQS implements RequestHandler<SQSEvent, SQSBatchResponse> {

    /** Logger for failed message identifiers and processing errors. */
    private static final Logger LOG = LogManager.getLogger();
    /** Reusable JSON codec; never holds a request's payload between invocations. */
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Record persistence operation, replaceable within the package for offline tests. */
    private final Consumer<PostgresChange> writer;

    /** Creates the production consumer without connecting to PostgreSQL. */
    public PostgresAuditLogTriggerSQS() {
        this(PostgresAuditLogTriggerSQS::writeAudit);
    }

    /**
     * Creates a consumer with a supplied persistence operation for deterministic tests.
     *
     * @param writer operation that returns only after its audit write succeeds
     * @throws NullPointerException if the writer is null
     */
    PostgresAuditLogTriggerSQS(Consumer<PostgresChange> writer) {
        this.writer = Objects.requireNonNull(writer);
    }

    /**
     * Processes every record independently, allowing successful messages to be acknowledged.
     *
     * <p>Invalid or empty message bodies are failures, not successful no-ops. All message IDs
     * are checked before any writes, because a partial-failure response needs usable IDs.
     *
     * @param event batch from the standard SQS queue; an empty batch is a successful no-op
     * @param context Lambda metadata; unused
     * @return failed message IDs for Lambda to retry, with successful records omitted
     * @throws IllegalArgumentException if the batch or a message identifier is absent
     */
    @Override
    public SQSBatchResponse handleRequest(SQSEvent event, Context context) {
        if (event == null || event.getRecords() == null) {
            throw new IllegalArgumentException("SQS event requires records");
        }
        for (var message : event.getRecords()) {
            if (message == null || message.getMessageId() == null || message.getMessageId().isBlank()) {
                throw new IllegalArgumentException("SQS record requires a message ID");
            }
        }
        var failures = new ArrayList<SQSBatchResponse.BatchItemFailure>();
        for (var message : event.getRecords()) {
            try {
                var change = PostgresChange.fromJson(MAPPER.readTree(message.getBody()));
                writer.accept(change);
            } catch (Exception failure) {
                LOG.error("Failed to process SQS message {}", message.getMessageId(), failure);
                failures.add(new SQSBatchResponse.BatchItemFailure(message.getMessageId()));
            }
        }
        return new SQSBatchResponse(failures);
    }

    /**
     * Writes one audit row using a connection closed before this method returns.
     *
     * @param change validated operation and row images from the message body
     * @throws org.jooq.exception.DataAccessException if connection acquisition or insertion fails
     */
    private static void writeAudit(PostgresChange change) {
        try (var dsl = PostgresDataSource.getDSL()) {
            dsl.insertInto(DSL.table("audit_log_sqs"))
                    .set(DSL.field("operation"), change.operation().toString())
                    .set(DSL.field("table_name"), change.tableName())
                    .set(DSL.field("old_record", JSONB.class), change.oldRecord().isNull() ? null : JSONB.jsonb(change.oldRecord().toString()))
                    .set(DSL.field("new_record", JSONB.class), change.newRecord().isNull() ? null : JSONB.jsonb(change.newRecord().toString()))
                    .execute();
        }
    }
}
