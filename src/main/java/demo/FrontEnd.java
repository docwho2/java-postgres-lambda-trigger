package demo;

import java.util.HashMap;
import java.util.Map;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;

/**
 * Renders the demo's root page with address-action links and the contents of public tables.
 *
 * <p>{@link WebHandler} delegates {@code GET /} requests here. Tables are discovered from
 * {@code information_schema.tables} in name order, and their rows are displayed by descending
 * {@code id}. The view therefore assumes every discovered table has an {@code id} column.
 * It reads all rows without pagination and formats them using jOOQ's HTML renderer.
 */
public class FrontEnd implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    /**
     * Creates the table-view renderer; database access is deferred until a page request is handled.
     */
    public FrontEnd() {
    }

    /** Logger for page requests and database query failures. */
    Logger log = LogManager.getLogger();
    
    /** Shared response headers declaring the generated page as HTML. */
    final static Map<String, String> headers = new HashMap<>();
    
    static {
         headers.put("Content-Type", "text/html");
    }
    
    
    /**
     * Reads the public schema and renders its tables together with the demo action controls.
     *
     * <p>Database query failures inside the rendering block are logged and converted to an
     * HTTP 500 response. Database-context acquisition occurs before that block, so initialization
     * failures are not converted by this method's exception handler.
     *
     * @param input API Gateway payload-format 1.0 request; used only for debug logging
     * @param context Lambda invocation metadata; unused by this implementation
     * @return HTTP 200 containing the generated HTML, or HTTP 500 containing a caught
     *         database exception's text; both responses have an HTML content type
     */
    @Override
    public APIGatewayProxyResponseEvent handleRequest(final APIGatewayProxyRequestEvent input, final Context context) {
        log.debug(input);
        var dsl = PostgresDataSource.getDSL();
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent()
                .withHeaders(headers);
        
        try {
            final var sb = renderHTMLStart();
            
            sb.append("<h3>Postgres Java Lambda Trigger Demo</h3>");
            sb.append("<a href=\"/create\" class=\"btn btn-primary\" role=\"button\">Add Row to Address</a>\n");
            sb.append("<a href=\"/multiple\" class=\"btn btn-primary\" role=\"button\">Add 5 Rows to Address</a>\n");
            sb.append("<a href=\"/delete\" class=\"btn btn-warning\" role=\"button\">Delete Last Address</a>\n");
            sb.append("<a href=\"javascript:location.reload()\" class=\"btn btn-primary\" role=\"button\">Refresh</a>\n");
            sb.append("<a href=\"/audit\" class=\"btn btn-warning pull-right\" role=\"button\">Clear Audit Log</a>\n");
            // List of tables in public
            final var table_name = DSL.field("table_name", String.class);
            final var tables = dsl.select(table_name)
                    .from("information_schema.tables")
                    .where(DSL.field("table_schema").eq("public"))
                    .orderBy(table_name)
                    .fetch(table_name);
                    
            if ( tables.isEmpty() ) {
                sb.append("<h2>No Tables exist in the Public Schema</h2>");
            } else {
                
                // Kick out each table
                for(var table : tables) {
                    sb.append(appendPanelStart("Table [" + table + "]"));
                    sb.append(dsl.select().from(table).orderBy(DSL.field("id").desc()).fetch().formatHTML()
                    .replace("<table>", "<table class=\"table table-striped table-bordered\" width=\"100%\">\n"));
                    sb.append(appendPanelEnd());
                }
            }
            
            sb.append(renderHTMLEnd());
            
            return response
                    .withStatusCode(200)
                    .withBody(sb.toString());
        } catch (DataAccessException e) {
            log.error("Front End Error", e);
            return response
                    .withBody(e.toString())
                    .withStatusCode(500);
        }
    }

    /**
     * Creates the opening Bootstrap panel markup for one database table.
     *
     * @param title panel heading inserted verbatim into HTML; callers must supply trusted
     *              or already escaped text because this method does not escape it
     * @return a new builder containing the panel heading and opening responsive body element
     */
    public StringBuilder appendPanelStart(String title) {
        var sb = new StringBuilder();
        sb.append("<div class=\"panel panel-default\">\n");
        sb.append("<div class=\"panel-heading\">").append(title);
        sb.append("</div>\n");
        sb.append("<div class=\"panel-body table-responsive\">\n");
        return sb;
    }

    /**
     * Creates the closing markup matching {@link #appendPanelStart(String)}.
     *
     * @return a new builder that closes the panel body and outer panel elements
     */
    public StringBuilder appendPanelEnd() {
        return new StringBuilder("</div></div>\n");
    }
    
    /**
     * Creates the page prologue, title, Bootstrap stylesheet link, and main container.
     *
     * @return a new builder with the document header and opening body/container markup
     */
    private StringBuilder renderHTMLStart() {
        var sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n");
        sb.append("<HTML><HEAD>\n");
        sb.append("<TITLE>Postgres Lambda Trigger Demo</TITLE>\n");
        sb.append("<link rel=\"stylesheet\" href=\"https://maxcdn.bootstrapcdn.com/bootstrap/3.3.7/css/bootstrap.min.css\" integrity=\"sha384-BVYiiSIFeK1dGmJRAkycuHAHRg32OmUcww7on3RYdg4Va+PmSTsz/K68vbdEjh4u\" crossorigin=\"anonymous\">\n");
        sb.append("</HEAD><BODY>\n");
        sb.append("<div class=\"container-fluid\">\n");
        return sb;
    }
    
    /**
     * Creates the page epilogue matching {@link #renderHTMLStart()}.
     *
     * @return a new builder that closes the main container, body, and HTML document
     */
    private StringBuilder renderHTMLEnd() {
        var sb = new StringBuilder();
        sb.append("</div>\n");
        sb.append("</BODY></HTML>\n");
        return sb;
    }
}
