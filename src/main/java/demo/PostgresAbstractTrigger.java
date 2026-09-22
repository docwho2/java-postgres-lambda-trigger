package demo;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Decodes row-change payloads produced by the PostgreSQL {@code record_change_lambda} function.
 *
 * <p>Payloads contain {@code TG_OP}, {@code TG_TABLE_NAME}, and the {@code old}/{@code new}
 * row images. Subclasses implement the geocoding or audit side effect. The database invokes
 * these handlers asynchronously, so the source transaction may not yet be committed.
 *
 * @author sjensen
 */
public abstract class PostgresAbstractTrigger implements RequestStreamHandler {

    /**
     * Creates a trigger handler with its logger and the shared JSON mapper.
     */
    public PostgresAbstractTrigger() {
    }
    /** Logger for decoded trigger payloads and processing failures. */
    final Logger log = LogManager.getLogger();

    /** Shared JSON mapper used to decode trigger payloads and encode response/database JSON. */
    final static ObjectMapper mapper = new ObjectMapper();

    /**
     * Reads a trigger payload, invokes the subclass, and writes a JSON acknowledgement.
     *
     * <p>Exceptions from operation/row extraction or {@link #processEvent(TG_OP, String, JsonNode, JsonNode)}
     * are logged and suppressed; the method still writes {@code {"status":"OK"}}. Such failures
     * are therefore not reported to Lambda for retries. Initial JSON parsing and response I/O
     * occur outside that catch block. Closing the response writer also closes {@code out}.
     *
     * @param in input stream containing the PostgreSQL row-change JSON document
     * @param out output stream receiving the UTF-8 JSON acknowledgement
     * @param cntxt Lambda invocation metadata; unused by this implementation
     * @throws IOException if reading/parsing the input or writing/closing the response fails
     */
    @Override
    public final void handleRequest(InputStream in, OutputStream out, Context cntxt) throws IOException {
        // Read in JSON Tree
        var json = mapper.readTree(in);
        log.debug("INPUT JSON is " + json.toPrettyString());

        try {
            final var operation = TG_OP.valueOf(json.findValue("TG_OP").asText());
            final var table_name = json.findValue("TG_TABLE_NAME").asText();
            final var old_record = json.findValue("old");
            final var new_record = json.findValue("new");

            processEvent(operation, table_name, old_record, new_record);
        } catch (Exception e) {
            log.error("Error Processing Event", e);
        }

        try (Writer w = new OutputStreamWriter(out, "UTF-8")) {
            w.write(mapper.createObjectNode().put("status", "OK").toString());
        }
    }

    /**
     * Applies a subclass-specific side effect for one decoded row-change event.
     *
     * <p>The normal payload uses JSON null for the row image that does not exist for an
     * INSERT or DELETE. Missing fields can also reach this method as Java {@code null};
     * the base handler does not validate row shapes before dispatch.
     *
     * @param operation PostgreSQL operation decoded from {@code TG_OP}
     * @param table_name source table name from {@code TG_TABLE_NAME}
     * @param old_record pre-change row image, JSON null for INSERT, or {@code null} if absent
     * @param new_record post-change row image, JSON null for DELETE, or {@code null} if absent
     */
    protected abstract void processEvent(TG_OP operation, String table_name, JsonNode old_record, JsonNode new_record);

    /**
     * Supported PostgreSQL row-change operations, matching the database's uppercase {@code TG_OP} values.
     */
    public enum TG_OP {
        /** A new row was inserted; only the new row image is populated. */
        INSERT,
        /** An existing row was changed; both old and new row images are populated. */
        UPDATE,
        /** A row was removed; only the old row image is populated. */
        DELETE
    }
}
