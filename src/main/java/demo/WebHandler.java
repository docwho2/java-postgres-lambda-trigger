package demo;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.util.Map;

/** Routes all UI requests within one Lambda execution environment. */
public class WebHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final Map<String, RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent>> routes;

    public WebHandler() {
        this(Map.of(
                "/", new FrontEnd(),
                "/create", new CreateAddressFrontEnd(),
                "/multiple", new CreateMultipleAddressFrontEnd(),
                "/delete", new DeleteAddressFrontEnd(),
                "/audit", new DeleteAuditLogFrontEnd()));
    }

    WebHandler(Map<String, RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent>> routes) {
        // Reuse stateless handlers across warm requests; never retain request data here.
        this.routes = Map.copyOf(routes);
    }

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

    private static APIGatewayProxyResponseEvent error(int status, String message) {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(status)
                .withHeaders(Map.of("Content-Type", "text/plain; charset=utf-8"))
                .withBody(message);
    }
}
