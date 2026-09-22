package demo;

import java.util.HashMap;
import java.util.Map;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;

/**
 * Executes a database action for the demo UI and redirects the browser to the table view.
 *
 * <p>Subclasses supply the database operation through {@link #performAction()}.
 * {@link WebHandler} reuses these handlers within the shared UI Lambda. A successful
 * action returns HTTP 307; exceptions from the action are logged and returned as HTTP 500
 * with the exception text. Both responses retain the shared redirect and cache headers.
 */
public abstract class AbstractActionFrontEnd implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    /**
     * Creates an action handler with its request logger; database work begins only when invoked.
     */
    public AbstractActionFrontEnd() {
    }

    /** Logger for incoming requests and database-action failures. */
    Logger log = LogManager.getLogger();


    /** Unqualified demo address table, resolved through the database connection's search path. */
    protected final static Table<Record> ADDRESS_TABLE = DSL.table("address");
    
    /** Shared response headers that disable caching and identify the root redirect target. */
    final static Map<String, String> headers = new HashMap<>();
    
    static {
        headers.put("Cache-Control", "no-cache");

        // Redirect back to main page
        headers.put("Location", "/");
    }
    
    /**
     * Performs the subclass's database mutation before the redirect response is returned.
     *
     * <p>Implementations establish their own database access and transaction boundaries.
     * Exceptions are handled by {@link #handleRequest(APIGatewayProxyRequestEvent, Context)}.
     */
    protected abstract void performAction();

    /**
     * Executes the action once and builds the existing redirect or error response.
     *
     * @param input API Gateway payload-format 1.0 request; logged but not used to select data
     * @param context Lambda invocation metadata; unused by this implementation
     * @return HTTP 307 with {@code Location: /} on success, or HTTP 500 with the exception
     *         text on failure; both responses include the shared headers
     */
    @Override
    public APIGatewayProxyResponseEvent handleRequest(final APIGatewayProxyRequestEvent input, final Context context) {
        log.debug(input);

        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent()
                .withHeaders(headers)
                // Redirect Temp
                .withStatusCode(307);
        try {
            performAction();
            return response;
        } catch (Exception e) {
            log.error("Front End Error", e);
            return response
                    .withBody(e.toString())
                    .withStatusCode(500);
        }
    }

}
