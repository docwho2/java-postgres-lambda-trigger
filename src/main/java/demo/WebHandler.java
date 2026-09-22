package demo;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.util.Map;

/**
 * Routes the demo's five HTTP endpoints inside one Lambda execution environment.
 *
 * <p>The SAM template uses HTTP API payload format 1.0 to populate
 * {@link APIGatewayProxyRequestEvent}. Exact, case-sensitive paths select reusable handlers
 * for the table view and address/audit mutations. Dispatch is an ordinary Java call and
 * preserves the selected handler's response without invoking another Lambda.
 */
public class WebHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    /** Immutable mapping of exact URL paths to handlers reused across warm invocations. */
    private final Map<String, RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent>> routes;

    /**
     * Creates the production router for {@code /}, {@code /create}, {@code /multiple},
     * {@code /delete}, and {@code /audit} without opening a database connection.
     */
    public WebHandler() {
        this(Map.of(
                "/", new FrontEnd(),
                "/create", new CreateAddressFrontEnd(),
                "/multiple", new CreateMultipleAddressFrontEnd(),
                "/delete", new DeleteAddressFrontEnd(),
                "/audit", new DeleteAuditLogFrontEnd()));
    }

    /**
     * Creates a router with supplied handlers for deterministic, offline routing tests.
     *
     * @param routes exact request paths and their handlers; copied so later map mutations
     *               cannot alter routing, although handler instances themselves are shared
     * @throws NullPointerException if the map, any path, or any handler is {@code null}
     */
    WebHandler(Map<String, RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent>> routes) {
        // Reuse stateless handlers across warm requests; never retain request data here.
        this.routes = Map.copyOf(routes);
    }

    /**
     * Validates a request, selects its exact path, and dispatches supported GET requests.
     *
     * <p>Validation precedes handler execution: missing request fields produce HTTP 400,
     * unknown paths produce HTTP 404, and non-GET methods on known paths produce HTTP 405
     * with {@code Allow: GET}. Query parameters do not participate in path selection.
     * Uncaught exceptions from the selected handler propagate to the Lambda runtime.
     *
     * @param input API Gateway payload-format 1.0 request; {@code null} is treated as invalid
     * @param context invocation metadata forwarded unchanged to the selected handler
     * @return the selected handler's original response, or a plain-text validation response
     */
    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        // SAM explicitly selects payload version 1.0, matching the existing UI handlers.
        if (input == null || input.getPath() == null || input.getHttpMethod() == null) {
            return error(400, "Invalid HTTP request");
        }
        var handler = routes.get(input.getPath());
        if (handler == null) {
            return error(404, "Not found");
        }
        if (!"GET".equals(input.getHttpMethod())) {
            return error(405, "Method not allowed")
                    .withHeaders(Map.of("Content-Type", "text/plain; charset=utf-8", "Allow", "GET"));
        }

        // Call the existing implementation directly, without another Lambda invocation.
        return handler.handleRequest(input, context);
    }

    /**
     * Builds a fresh plain-text response for a request rejected before dispatch.
     *
     * @param status HTTP response status code
     * @param message response body describing the request error
     * @return a response containing the supplied status and message with a UTF-8 text content type
     */
    private static APIGatewayProxyResponseEvent error(int status, String message) {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(status)
                .withHeaders(Map.of("Content-Type", "text/plain; charset=utf-8"))
                .withBody(message);
    }
}
