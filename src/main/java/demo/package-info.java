/**
 * Demonstrates PostgreSQL row-change triggers invoking AWS Lambda for geocoding and audit logging.
 *
 * <p>{@link demo.WebHandler} serves the UI through a single Lambda and delegates database
 * actions to Java handlers. PostgreSQL asynchronously invokes {@link demo.PostgresAddressTrigger}
 * and {@link demo.PostgresAuditLogTrigger}; a separate Node.js forwarder sends the same audit
 * payload to SQS for {@link demo.PostgresAuditLogTriggerSQS}. Database initialization is managed
 * by {@link demo.CloudFormationCustomResource}, and {@link demo.PostgresDataSource} supplies
 * Secrets Manager-backed JDBC connections.
 *
 * <p>The implementation is a demonstration: individual handlers document their current
 * connection lifetime, error acknowledgement, and retry behavior. Trigger delivery does not
 * establish that the originating database transaction has committed.
 */
package demo;
