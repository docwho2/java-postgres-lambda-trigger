package demo;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.impl.DSL;

/**
 * Records PostgreSQL row-change payloads delivered through SQS in {@code audit_log_sqs}.
 *
 * <p>The forwarding Lambda places the original trigger JSON in each message body. This
 * consumer processes only the first record, matching the SAM event source's batch size of one.
 * It holds a database context for the execution environment's lifetime and does not deduplicate
 * messages. Processing exceptions are logged and suppressed, so returning normally can acknowledge
 * a message whose audit insert failed.
 */
public class PostgresAuditLogTriggerSQS implements RequestHandler<SQSEvent, Void> {

    /**
     * Creates the queue consumer after class initialization has opened the shared database context.
     */
    public PostgresAuditLogTriggerSQS() {
    }

    /** Logger for queue payloads, empty message bodies, and processing failures. */
    final Logger log = LogManager.getLogger();

    /** Database context opened during class initialization and reused across invocations. */
    final static DSLContext dsl = PostgresDataSource.getDSL();

    /** Shared mapper used to parse the original PostgreSQL trigger JSON from message bodies. */
    final static ObjectMapper mapper = new ObjectMapper();

    /**
     * Parses the first SQS message and inserts its operation and row images into the audit table.
     *
     * <p>Empty bodies are logged and skipped. Other parsing or database exceptions inside
     * the method are logged and suppressed. Additional records in a larger batch would be
     * ignored, so the event source must retain its batch size of one. This handler does not
     * return partial batch failures or throw processing errors for SQS retries.
     *
     * @param event SQS event expected to contain exactly one record with PostgreSQL trigger JSON
     * @param context Lambda invocation metadata; unused by this implementation
     * @return always {@code null}, including when a message is skipped or processing fails
     */
    @Override
    public Void handleRequest(SQSEvent event, Context context) {
        try {
            // We will only ever get one message set on the trigger
            SQSEvent.SQSMessage mesg = event.getRecords().get(0);
            String body = mesg.getBody();

            if (body == null || body.isEmpty()) {
                log.error("Message Body is empty, ignoring message");
                return null;
            }
            // Read in JSON Tree 
            var json = mapper.readTree(body);
            log.debug("INPUT EVENT JSON is " + json.toPrettyString());

            final var operation = json.findValue("TG_OP").asText();
            final var table_name = json.findValue("TG_TABLE_NAME").asText();
            final var old_record = json.findValue("old");
            final var new_record = json.findValue("new");

            // Just insert an Audit row for the operation
            dsl.insertInto(DSL.table("audit_log_sqs"))
                    .set(DSL.field("operation"), operation)
                    .set(DSL.field("table_name"), table_name)
                    .set(DSL.field("old_record", JSONB.class), old_record == null || old_record.isNull() ? null : JSONB.jsonb(old_record.toString()))
                    .set(DSL.field("new_record", JSONB.class), new_record == null || new_record.isNull() ? null : JSONB.jsonb(new_record.toString()))
                    .execute();

        } catch (Exception e) {
            log.error("Error Processing Event", e);
        }

        return null;
    }

}
