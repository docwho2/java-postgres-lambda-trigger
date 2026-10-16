package demo;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.CloudFormationCustomResourceEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jooq.CloseableDSLContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jooq.impl.DSL;
import software.amazon.lambda.powertools.cloudformation.AbstractCustomResourceHandler;
import software.amazon.lambda.powertools.cloudformation.Response;

/**
 * Initializes the demo database through a CloudFormation custom resource after RDS provisioning.
 *
 * <p>The Powertools superclass dispatches lifecycle events and delivers responses to
 * CloudFormation. CREATE executes the packaged SQL scripts in dependency order and inserts
 * one sample address. UPDATE and DELETE acknowledge the event without modifying the database.
 * CREATE stops at the first failure and returns a FAILED response. Scripts are loaded from
 * the packaged classpath, so initialization also works when resources are inside a JAR.
 *
 * @author sjensen
 */
public class CloudFormationCustomResource extends AbstractCustomResourceHandler {

    /**
     * Creates the database-initialization lifecycle handler and its Powertools response support.
     */
    public CloudFormationCustomResource() {
        this(PostgresDataSource::getDSL, CloudFormationCustomResource::readScript);
    }

    /** Connection factory invoked only during CREATE. */
    private final Supplier<CloseableDSLContext> database;
    /** Packaged SQL loader, replaceable within the package for offline tests. */
    private final Function<String, String> scripts;
    /** SQL assets in dependency order; all are read before any database changes. */
    private static final List<String> SQL_FILES = List.of("enableLambdaExtension.sql",
            "LambdaTriggerFuction.sql", "createAddressTable.sql", "createAuditLogTable.sql",
            "createAddressTrigger.sql");

    /**
     * Supplies initialization boundaries for offline lifecycle and SQL failure tests.
     *
     * @param database factory producing a context owned by the CREATE callback
     * @param scripts SQL reader keyed by packaged filename
     * @throws NullPointerException if either dependency is null
     */
    CloudFormationCustomResource(Supplier<CloseableDSLContext> database, Function<String, String> scripts) {
        this.database = Objects.requireNonNull(database);
        this.scripts = Objects.requireNonNull(scripts);
    }

    /** Logger for CloudFormation lifecycle events and SQL initialization failures. */
    Logger log = LogManager.getLogger();
    
    /** Stable physical resource ID returned across CREATE, UPDATE, and DELETE callbacks. */
    private final static String RESOURCE_ID = "db_sql_initialize";

    /**
     * Executes the extension, trigger-function, table, and trigger scripts, then seeds an address.
     *
     * <p>All resources are loaded before database work. Execution stops on the first failed
     * statement, and the seed insert runs only after all scripts succeed. The connection is
     * always closed. DDL and the seed are not one transaction: previously completed scripts
     * may remain after failure, and repeated CREATE callbacks can duplicate the sample row.
     *
     * @param cfcre CloudFormation CREATE event; not used as a SQL input
     * @param cntxt Lambda metadata; unused
     * @return SUCCESS after all statements finish, or FAILED with a stable physical ID;
     *         detailed errors are logged without exposing them in the response reason
     */
    @Override
    protected Response create(CloudFormationCustomResourceEvent cfcre, Context cntxt) {
        try {
            // A missing asset must be discovered before applying even the first script.
            var statements = SQL_FILES.stream().map(scripts).toList();
            try (var dsl = database.get()) {
                for (var statement : statements) {
                    dsl.execute(statement);
                }
                dsl.insertInto(DSL.table(DSL.name("address")))
                        .set(DSL.field("address_1"), "200 N Main St")
                        .set(DSL.field("city"), "Wahkon")
                        .set(DSL.field("district"), "MN")
                        .set(DSL.field("address_notes"), "Mugg's of Mille Lacs")
                        .execute();
            }
            return Response.builder().physicalResourceId(RESOURCE_ID)
                    .status(Response.Status.SUCCESS).reason("SQL files applied to database").build();
        } catch (RuntimeException failure) {
            log.error("Database initialization failed", failure);
            // Return via Powertools so CloudFormation receives a failure callback, not a timeout.
            return Response.builder().physicalResourceId(RESOURCE_ID)
                    .status(Response.Status.FAILED).reason("Database initialization failed; see Lambda logs").build();
        }
    }

    /**
     * Reads a SQL resource from the same classpath used by Lambda and local Java execution.
     *
     * @param filename packaged SQL filename under {@code /scripts/}
     * @return UTF-8 SQL text
     * @throws IllegalStateException if the resource is absent
     * @throws UncheckedIOException if the resource cannot be read or closed
     */
    static String readScript(String filename) {
        try (var stream = CloudFormationCustomResource.class.getResourceAsStream("/scripts/" + filename)) {
            if (stream == null) {
                throw new IllegalStateException("Missing packaged SQL script: " + filename);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot read packaged SQL script: " + filename, failure);
        }
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
