package demo;

/**
 * Handles the {@code /audit} UI action by clearing both demo audit tables.
 *
 * <p>This action truncates {@code audit_log} and {@code audit_log_sqs}; it does not delete
 * addresses or purge queued/in-flight events, which may subsequently add audit records.
 */
public class DeleteAuditLogFrontEnd extends AbstractActionFrontEnd {

    /**
     * Creates the audit-clear action without truncating either audit table.
     */
    public DeleteAuditLogFrontEnd() {
    }

    /**
     * Truncates the direct audit table followed by the SQS audit table.
     *
     * <p>The statements have no enclosing transaction here, so the first truncation can
     * succeed even if the second fails. Neither statement requests an identity reset.
     *
     * @throws org.jooq.exception.DataAccessException if either truncate fails; the inherited
     *         request handler converts this exception to an HTTP 500 response
     */
    @Override
    protected void performAction() {
        // Clear out the audit log
        var dsl = PostgresDataSource.getDSL();
        dsl.truncate("audit_log").execute();
        dsl.truncate("audit_log_sqs").execute();
    }

}
