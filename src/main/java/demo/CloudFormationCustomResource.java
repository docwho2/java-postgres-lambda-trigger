/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
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
 * Custom resource called from Cloud Formation after the RDS instance is done provisioning. This lambda is responsible
 * for running all the SQL files in the correct order to initialize the DB.  Care should be taken to never throw an
 * Exception back or this will stall the stack operation and error the stack.
 *
 * @author sjensen
 */
public class CloudFormationCustomResource extends AbstractCustomResourceHandler {

    // Initialize the Log4j logger.
    Logger log = LogManager.getLogger();
    
    private final static String RESOURCE_ID = "db_sql_initialize";

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
     * We don't do anything on stack updates, just return null
     * @param cfcre
     * @param cntxt
     * @return 
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
     * We don't need to do anything on stack delete, just return null
     * @param cfcre
     * @param cntxt
     * @return 
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
