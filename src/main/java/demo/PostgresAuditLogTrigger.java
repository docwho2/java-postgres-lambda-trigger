package demo;

import com.fasterxml.jackson.databind.JsonNode;
import org.jooq.JSONB;
import org.jooq.impl.DSL;

/**
 * Records directly delivered PostgreSQL row-change events in {@code audit_log}.
 *
 * <p>Each invocation stores the operation, source table name, and available before/after
 * row images. Identity and creation time come from table defaults. Inserts are not deduplicated,
 * so repeated delivery can create repeated audit entries. The source address row is not modified.
 *
 * @author sjensen
 */
public class PostgresAuditLogTrigger extends PostgresAbstractTrigger {

    /**
     * Creates the direct-audit handler; an audit connection is opened when an event is processed.
     */
    public PostgresAuditLogTrigger() {
    }

    /**
     * Inserts one audit row, converting missing or JSON-null row images to SQL NULL.
     *
     * @param operation PostgreSQL INSERT, UPDATE, or DELETE operation stored as text
     * @param table_name source table name stored in the {@code table_name} column
     * @param old_record pre-change image, or Java/JSON null when no old image exists
     * @param new_record post-change image, or Java/JSON null when no new image exists
     * @throws org.jooq.exception.DataAccessException if the insert fails; the base stream
     *         handler logs and suppresses this failure under its current acknowledgement policy
     */
    @Override
    protected void processEvent(TG_OP operation, String table_name, JsonNode old_record, JsonNode new_record) {

        // Just insert an Audit row for the operation
        var dsl = PostgresDataSource.getDSL();
        dsl.insertInto(DSL.table(DSL.name("audit_log")))
                .set(DSL.field("operation"), operation.toString())
                .set(DSL.field("table_name"), table_name)
                .set(DSL.field("old_record",JSONB.class), old_record == null || old_record.isNull() ? null : JSONB.jsonb(old_record.toString()))
                .set(DSL.field("new_record",JSONB.class), new_record == null || new_record.isNull() ? null : JSONB.jsonb(new_record.toString()))
                .execute();
    }

}
