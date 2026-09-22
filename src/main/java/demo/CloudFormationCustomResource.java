package demo;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.CloudFormationCustomResourceEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import software.amazon.lambda.powertools.cloudformation.AbstractCustomResourceHandler;
import software.amazon.lambda.powertools.cloudformation.Response;

/**
 * Initializes the demo database through a CloudFormation custom resource after RDS provisioning.
 *
 * <p>The Powertools superclass dispatches lifecycle events and delivers responses to
 * CloudFormation. CREATE executes the packaged SQL scripts in dependency order and inserts
 * one sample address. UPDATE and DELETE acknowledge the event without modifying the database.
 * Caught setup errors are only logged, so the returned CREATE response is not proof that
 * every script or the sample insert succeeded.
 *
 * @author sjensen
 */
public class CloudFormationCustomResource extends AbstractCustomResourceHandler {

    /**
     * Creates the database-initialization lifecycle handler and its Powertools response support.
     */
    public CloudFormationCustomResource() {
    }

    /** Logger for CloudFormation lifecycle events and SQL initialization failures. */
    Logger log = LogManager.getLogger();
    
    /** Stable physical resource ID returned across CREATE, UPDATE, and DELETE callbacks. */
    private final static String RESOURCE_ID = "db_sql_initialize";

    /**
     * Executes the extension, trigger-function, table, and trigger scripts, then seeds an address.
     *
     * <p>Scripts are read from {@code LAMBDA_TASK_ROOT/scripts}. Each script's I/O or SQL
     * failure is logged and execution continues to the next script. An outer database exception
     * is also logged before returning the normal response. The scripts and seed insert are
     * not enclosed in a single transaction, and repeated CREATE processing can insert another
     * sample address. Uncaught runtime failures are left to the Powertools superclass.
     *
     * @param cfcre CloudFormation CREATE event; supplied to debug logging but not used for SQL inputs
     * @param cntxt Lambda invocation metadata; unused by this callback
     * @return a response with the stable physical resource ID and the reason
     *         {@code SQL files applied to database}, even after the caught failures described above
     */
    @Override
    protected Response create(CloudFormationCustomResourceEvent cfcre, Context cntxt) {
        try {
            log.debug("Received CREATE Event from Cloudformation", cfcre);
            final var dsl = PostgresDataSource.getDSL();
            final var task_root = System.getenv("LAMBDA_TASK_ROOT");

            final var sqlFiles = new LinkedList<String>();
            // Process the list in order
            sqlFiles.add("enableLambdaExtension.sql");
            sqlFiles.add("LambdaTriggerFuction.sql");
            sqlFiles.add("createAddressTable.sql");
            sqlFiles.add("createAuditLogTable.sql");
            sqlFiles.add("createAddressTrigger.sql");

            for (var file : sqlFiles) {
                try {
                    dsl.execute(Files.readString(Path.of(task_root, "scripts", file)));
                } catch (IOException | DataAccessException e) {
                    log.error("Error processing SQL file " + file, e);
                }
            }

            // Just Insert One address to kick things off
            dsl.insertInto(DSL.table(DSL.name("address")))
                    .set(DSL.field("address_1"), "200 N Main St")
                    .set(DSL.field("city"), "Wahkon")
                    .set(DSL.field("district"), "MN")
                    .set(DSL.field("address_notes"), "Mugg's of Mille Lacs")
                    .execute();
            

        } catch (DataAccessException e) {
            log.error("Could Not Process SQL Files", e);
        }
        return Response.builder()
                .reason("SQL files applied to database")
                .physicalResourceId(RESOURCE_ID)
                .build();
    }

    /**
     * Acknowledges a stack update without rerunning initialization or applying schema changes.
     *
     * @param cfcre CloudFormation UPDATE event, including old/new properties; used only for logging
     * @param cntxt Lambda invocation metadata; unused by this callback
     * @return a response with the stable physical resource ID and the reason {@code UPDATE event ignored}
     */
    @Override
    protected Response update(CloudFormationCustomResourceEvent cfcre, Context cntxt) {
        log.debug("Received UPDATE Event from Cloudformation", cfcre);
        return Response.builder()
                .physicalResourceId(RESOURCE_ID)
                .reason("UPDATE event ignored")
                .build();
    }

    /**
     * Acknowledges custom-resource deletion without issuing SQL or deleting database contents.
     *
     * <p>Deletion of the actual RDS resources is governed separately by their CloudFormation
     * definitions and deletion policies.
     *
     * @param cfcre CloudFormation DELETE event; used only for debug logging
     * @param cntxt Lambda invocation metadata; unused by this callback
     * @return a response with the stable physical resource ID and the reason {@code DELETE event ignored}
     */
    @Override
    protected Response delete(CloudFormationCustomResourceEvent cfcre, Context cntxt) {
        log.debug("Received DELETE Event from Cloudformation", cfcre);
        return Response.builder()
                .physicalResourceId(RESOURCE_ID)
                .reason("DELETE event ignored")
                .build();
    }

}
